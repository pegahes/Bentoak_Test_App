package com.getbouncer.scan.framework

import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
import com.getbouncer.scan.framework.api.FileCreationException
import com.getbouncer.scan.framework.api.NetworkResult
import com.getbouncer.scan.framework.api.downloadFileWithRetries
import com.getbouncer.scan.framework.api.getModelDetails
import com.getbouncer.scan.framework.api.getModelSignedUrl
import com.getbouncer.scan.framework.time.ClockMark
import com.getbouncer.scan.framework.time.asEpochMillisecondsClockMark
import com.getbouncer.scan.framework.time.days
import com.getbouncer.scan.framework.time.weeks
import com.getbouncer.scan.framework.util.memoizeSuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.lang.Exception
import java.net.URL
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

private val CACHE_MODEL_TIME = 1.weeks
private const val CACHE_MODEL_MAX_COUNT = 3

private const val PURPOSE_MODEL_UPGRADE = "model_upgrade"

/**
 * Fetched data metadata.
 */
sealed class FetchedModelMeta(open val modelVersion: String, open val hashAlgorithm: String)
data class FetchedModelFileMeta(
    override val modelVersion: String,
    override val hashAlgorithm: String,
    val modelFile: File?,
) : FetchedModelMeta(modelVersion, hashAlgorithm)

data class FetchedModelResourceMeta(
    override val modelVersion: String,
    override val hashAlgorithm: String,
    val hash: String,
    @RawRes val resourceId: Int?,
) : FetchedModelMeta(modelVersion, hashAlgorithm)

/**
 * Fetched data information.
 */
sealed class FetchedData(
    open val modelClass: String,
    open val modelFrameworkVersion: Int,
    open val modelVersion: String,
    open val modelHash: String?,
    open val modelHashAlgorithm: String?,
) {
    companion object {
        fun fromFetchedModelMeta(modelClass: String, modelFrameworkVersion: Int, meta: FetchedModelMeta) = when (meta) {
            is FetchedModelFileMeta ->
                FetchedFile(
                    modelClass = modelClass,
                    modelFrameworkVersion = modelFrameworkVersion,
                    modelVersion = meta.modelVersion,
                    modelHash = meta.modelFile?.let { runBlocking { calculateHash(it, meta.hashAlgorithm) } },
                    modelHashAlgorithm = meta.hashAlgorithm,
                    file = meta.modelFile
                )
            is FetchedModelResourceMeta ->
                FetchedResource(
                    modelClass = modelClass,
                    modelFrameworkVersion = modelFrameworkVersion,
                    modelVersion = meta.modelVersion,
                    modelHash = meta.hash,
                    modelHashAlgorithm = meta.hashAlgorithm,
                    resourceId = meta.resourceId,
                )
        }
    }

    abstract val successfullyFetched: Boolean
}

data class FetchedResource(
    override val modelClass: String,
    override val modelFrameworkVersion: Int,
    override val modelVersion: String,
    override val modelHash: String?,
    override val modelHashAlgorithm: String?,
    @RawRes val resourceId: Int?,
) : FetchedData(modelClass, modelFrameworkVersion, modelVersion, modelHash, modelHashAlgorithm) {
    override val successfullyFetched: Boolean = resourceId != null
}

data class FetchedFile(
    override val modelClass: String,
    override val modelFrameworkVersion: Int,
    override val modelVersion: String,
    override val modelHash: String?,
    override val modelHashAlgorithm: String?,
    val file: File?,
) : FetchedData(modelClass, modelFrameworkVersion, modelVersion, modelHash, modelHashAlgorithm) {
    override val successfullyFetched: Boolean = modelHash != null
}

/**
 * An interface for getting data ready to be loaded into memory.
 */
interface Fetcher {
    val modelClass: String
    val modelFrameworkVersion: Int

    /**
     * Prepare data to be loaded into memory. If the fetched data is to be used immediately, the fetcher will prioritize
     * fetching from the cache over getting the latest version.
     *
     * @param forImmediateUse: if there is a cached version of the model, return that immediately instead of downloading a new model
     */
    suspend fun fetchData(forImmediateUse: Boolean, isOptional: Boolean): FetchedData
}

/**
 * A [Fetcher] that gets data from android resources.
 */
abstract class ResourceFetcher : Fetcher {
    protected abstract val modelVersion: String
    protected abstract val hash: String
    protected abstract val hashAlgorithm: String
    protected abstract val resource: Int

    override suspend fun fetchData(forImmediateUse: Boolean, isOptional: Boolean): FetchedResource =
        FetchedResource(
            modelClass = modelClass,
            modelFrameworkVersion = modelFrameworkVersion,
            modelVersion = modelVersion,
            modelHash = hash,
            modelHashAlgorithm = hashAlgorithm,
            resourceId = resource,
        )
}

/**
 * A [Fetcher] that downloads data from the web.
 */
sealed class WebFetcher(protected val context: Context) : Fetcher {
    protected data class DownloadDetails(val url: URL, val hash: String, val hashAlgorithm: String, val modelVersion: String)

    /**
     * Keep track of any exceptions that occurred when fetching data  after the specified number of retries. This is
     * used to prevent the fetcher from repeatedly trying to fetch the data from multiple threads after the number of
     * retries has been reached.
     */
    private var fetchException: Throwable? = null

    override suspend fun fetchData(forImmediateUse: Boolean, isOptional: Boolean): FetchedData {
        val stat = Stats.trackPersistentRepeatingTask("web_fetcher_$modelClass")
        val cachedData = FetchedData.fromFetchedModelMeta(modelClass, modelFrameworkVersion, tryFetchLatestCachedData())

        // attempt to fetch the data from local cache if it's needed immediately
        if (forImmediateUse) {
            tryFetchLatestCachedData().run {
                val data = FetchedData.fromFetchedModelMeta(modelClass, modelFrameworkVersion, this)
                if (data.successfullyFetched) {
                    stat.trackResult("success")
                    return@fetchData data
                }
            }
        }

        // get details for downloading the data. If download details cannot be retrieved, use the latest cached version
        val downloadDetails = fetchDownloadDetails(cachedData.modelHash, cachedData.modelHashAlgorithm) ?: run {
            Log.d(Config.logTag, "Not downloading $modelClass, using cached version ${cachedData.modelVersion}")
            stat.trackResult("no_download_details")
            return@fetchData cachedData
        }

        // if no cache is available, this is needed immediately, and this is optional, return a download failure
        if (forImmediateUse && isOptional) {
            Log.d(Config.logTag, "Optional $modelClass needed for immediate use, but no cache available.")
            return FetchedData.fromFetchedModelMeta(
                modelClass = modelClass,
                modelFrameworkVersion = modelFrameworkVersion,
                meta = FetchedModelFileMeta(
                    modelVersion = downloadDetails.modelVersion,
                    hashAlgorithm = downloadDetails.hashAlgorithm,
                    modelFile = null
                )
            )
        }

        return try {
            // check the local cache for a matching model
            tryFetchMatchingCachedFile(downloadDetails.hash, downloadDetails.hashAlgorithm).run {
                val data = FetchedData.fromFetchedModelMeta(modelClass, modelFrameworkVersion, this)
                if (data.successfullyFetched) {
                    stat.trackResult("success")
                    return@fetchData data
                }
            }

            downloadData(downloadDetails)
        } catch (t: Throwable) {
            fetchException = t
            if (cachedData.successfullyFetched) {
                Log.w(Config.logTag, "Failed to download model $modelClass, loaded from local cache", t)
                stat.trackResult("success")
            } else {
                Log.e(Config.logTag, "Failed to download model $modelClass, no local cache available", t)
                stat.trackResult(t::class.java.simpleName)
            }
            cachedData
        }
    }

    /**
     * Get information about what version of the model to download.
     */
    private val fetchDownloadDetails = memoizeSuspend(3.days) { cachedHash: String?, cachedHashAlgorithm: String? ->
        getDownloadDetails(cachedHash, cachedHashAlgorithm)
    }

    /**
     * Download the data using memoization so that data is only downloaded once.
     */
    private val downloadData = memoizeSuspend { downloadDetails: DownloadDetails ->
        val downloadOutputFile = getDownloadOutputFile(downloadDetails.modelVersion)

        // if a previous exception was encountered, attempt to fetch cached data
        fetchException?.run {
            Log.d(Config.logTag, "Previous exception encountered for $modelClass, rethrowing")
            throw this
        }

        try {
            downloadAndVerify(
                context = context,
                url = downloadDetails.url,
                outputFile = downloadOutputFile,
                hash = downloadDetails.hash,
                hashAlgorithm = downloadDetails.hashAlgorithm,
            )

            Log.d(Config.logTag, "$modelClass downloaded version ${downloadDetails.modelVersion}")
            return@memoizeSuspend FetchedFile(
                modelClass = modelClass,
                modelFrameworkVersion = modelFrameworkVersion,
                modelVersion = downloadDetails.modelVersion,
                modelHash = downloadDetails.hash,
                modelHashAlgorithm = downloadDetails.hashAlgorithm,
                file = downloadOutputFile,
            )
        } finally {
            cleanUpPostDownload(downloadOutputFile)
        }
    }

    /**
     * Attempt to load the data from the local cache.
     */
    protected abstract suspend fun tryFetchLatestCachedData(): FetchedModelMeta

    /**
     * Attempt to load a cached data given the required [hash] and [hashAlgorithm].
     */
    protected abstract suspend fun tryFetchMatchingCachedFile(hash: String, hashAlgorithm: String): FetchedModelMeta

    /**
     * Get [DownloadDetails] for the data that will be downloaded.
     *
     * @param cachedModelHash: the hash of the cached model, or null if nothing is cached
     * @param cachedModelHashAlgorithm: the hash algorithm used to calculate the hash
     */
    protected abstract suspend fun getDownloadDetails(
        cachedModelHash: String?,
        cachedModelHashAlgorithm: String?,
    ): DownloadDetails?

    /**
     * Get the file where the data should be downloaded.
     */
    protected abstract suspend fun getDownloadOutputFile(modelVersion: String): File

    /**
     * After download, clean up.
     */
    protected abstract suspend fun cleanUpPostDownload(downloadedFile: File)

    /**
     * Clear the cache for this loader. This will force new downloads.
     */
    abstract suspend fun clearCache()
}

/**
 * A [WebFetcher] that directly downloads a model.
 */
abstract class DirectDownloadWebFetcher(context: Context) : WebFetcher(context) {
    abstract val url: URL
    abstract val hash: String
    abstract val hashAlgorithm: String
    abstract val modelVersion: String

    private val localFileName: String by lazy { url.path.replace('/', '_') }

    override suspend fun tryFetchLatestCachedData(): FetchedModelMeta {
        val localFile = getDownloadOutputFile(modelVersion)
        return if (fileMatchesHash(localFile, hash, hashAlgorithm)) {
            FetchedModelFileMeta(modelVersion, hashAlgorithm, localFile)
        } else {
            FetchedModelFileMeta(modelVersion, hashAlgorithm, null)
        }
    }

    override suspend fun tryFetchMatchingCachedFile(hash: String, hashAlgorithm: String): FetchedModelMeta =
        FetchedModelFileMeta(modelVersion, hashAlgorithm, null)

    override suspend fun getDownloadOutputFile(modelVersion: String) =
        File(context.cacheDir, localFileName)

    override suspend fun getDownloadDetails(
        cachedModelHash: String?,
        cachedModelHashAlgorithm: String?,
    ): DownloadDetails? =
        DownloadDetails(url, hash, hashAlgorithm, modelVersion)

    override suspend fun cleanUpPostDownload(downloadedFile: File) { /* nothing to do */ }

    override suspend fun clearCache() {
        val localFile = getDownloadOutputFile(modelVersion)
        if (localFile.exists()) {
            localFile.delete()
        }
    }
}

/**
 * A [WebFetcher] that uses the signed URL server endpoints to download data.
 */
abstract class SignedUrlModelWebFetcher(context: Context) : DirectDownloadWebFetcher(context) {
    abstract val modelFileName: String

    private val localFileName by lazy { "${modelClass}_${modelFileName}_$modelVersion" }

    // this field is not used by this class
    override val url: URL = URL(NetworkConfig.baseUrl)

    override suspend fun getDownloadOutputFile(modelVersion: String) = File(context.cacheDir, localFileName)

    override suspend fun getDownloadDetails(
        cachedModelHash: String?,
        cachedModelHashAlgorithm: String?,
    ) = when (val signedUrlResponse = getModelSignedUrl(context, modelClass, modelVersion, modelFileName)) {
        is NetworkResult.Success ->
            try {
                URL(signedUrlResponse.body.modelUrl)
            } catch (t: Throwable) {
                Log.e(Config.logTag, "Invalid signed url for model $modelClass: ${signedUrlResponse.body.modelUrl}", t)
                null
            }
        is NetworkResult.Error -> {
            Log.w(Config.logTag, "Failed to get signed url for model $modelClass: ${signedUrlResponse.error}")
            null
        }
        is NetworkResult.Exception -> {
            Log.e(Config.logTag, "Exception fetching signed url for model $modelClass: ${signedUrlResponse.responseCode}", signedUrlResponse.exception)
            null
        }
    }?.let { DownloadDetails(it, hash, hashAlgorithm, modelVersion) }
}

/**
 * A [WebFetcher] that queries Bouncer servers for updated data. If a new version is found, download it. If the data
 * details match what is cached, return the cached version instead.
 */
abstract class UpdatingModelWebFetcher(context: Context) : SignedUrlModelWebFetcher(context) {
    abstract val defaultModelVersion: String
    abstract val defaultModelFileName: String
    abstract val defaultModelHash: String
    abstract val defaultModelHashAlgorithm: String

    private var cachedDownloadDetails: DownloadDetails? = null

    private val getCacheFolder = memoizeSuspend<File> {
        ensureLocalFolder("${modelClass}_$modelFrameworkVersion")
    }

    override val modelVersion: String by lazy { defaultModelVersion }
    override val modelFileName: String by lazy { defaultModelFileName }
    override val hash: String by lazy { defaultModelHash }
    override val hashAlgorithm: String by lazy { defaultModelHashAlgorithm }

    override suspend fun tryFetchLatestCachedData(): FetchedModelMeta =
        getLatestFile()?.let { FetchedModelFileMeta(it.name, defaultModelHashAlgorithm, it) } ?: FetchedModelFileMeta(defaultModelVersion, defaultModelHashAlgorithm, null)

    override suspend fun tryFetchMatchingCachedFile(hash: String, hashAlgorithm: String): FetchedModelMeta =
        getMatchingFile(hash, hashAlgorithm)?.let { FetchedModelFileMeta(it.name, defaultModelHashAlgorithm, it) } ?: FetchedModelFileMeta(defaultModelVersion, defaultModelHashAlgorithm, null)

    override suspend fun getDownloadOutputFile(modelVersion: String) =
        File(getCacheFolder(), modelVersion)

    override suspend fun getDownloadDetails(
        cachedModelHash: String?,
        cachedModelHashAlgorithm: String?,
    ): DownloadDetails? {
        cachedDownloadDetails?.let { return DownloadDetails(url, hash, hashAlgorithm, modelVersion) }

        val nextUpgradeTime = getNextUpgradeTime()
        when {
            nextUpgradeTime.hasPassed() ->
                Log.d(Config.logTag, "Time to upgrade $modelClass, fetching upgrade details")
            cachedModelHash == null ->
                Log.d(Config.logTag, "Downloading initial version of $modelClass")
            else -> {
                Log.d(Config.logTag, "Not yet time to upgrade $modelClass (will upgrade at $nextUpgradeTime)")
                return null
            }
        }

        return when (
            val detailsResponse = getModelDetails(
                context = context,
                modelClass = modelClass,
                modelFrameworkVersion = modelFrameworkVersion,
                cachedModelHash = cachedModelHash,
                cachedModelHashAlgorithm = cachedModelHashAlgorithm,
            )
        ) {
            is NetworkResult.Success ->
                try {
                    detailsResponse.body.queryAgainAfterMs?.asEpochMillisecondsClockMark()?.apply {
                        setNextModelUpgradeAttemptTime(this)
                    }
                    detailsResponse.body.url?.let {
                        DownloadDetails(
                            url = URL(it),
                            hash = detailsResponse.body.hash,
                            hashAlgorithm = detailsResponse.body.hashAlgorithm,
                            modelVersion = detailsResponse.body.modelVersion,
                        ).apply { cachedDownloadDetails = this }
                    }
                } catch (t: Throwable) {
                    Log.e(Config.logTag, "Invalid signed url for model $modelClass: ${detailsResponse.body.url}", t)
                    null
                }
            is NetworkResult.Error -> {
                Log.w(Config.logTag, "Failed to get latest details for model $modelClass: ${detailsResponse.error}")
                fallbackDownloadDetails()
            }
            is NetworkResult.Exception -> {
                Log.e(Config.logTag, "Exception retrieving latest details for model $modelClass: ${detailsResponse.responseCode}", detailsResponse.exception)
                fallbackDownloadDetails()
            }
        }
    }

    /**
     * Determine if we should query for a model upgrade
     */
    protected open fun getNextUpgradeTime(): ClockMark =
        StorageFactory
            .getStorageInstance(context, PURPOSE_MODEL_UPGRADE)
            .getLong(modelClass, 0)
            .asEpochMillisecondsClockMark()

    protected open fun setNextModelUpgradeAttemptTime(time: ClockMark) {
        StorageFactory
            .getStorageInstance(context, PURPOSE_MODEL_UPGRADE)
            .storeValue(modelClass, time.toMillisecondsSinceEpoch())
    }

    /**
     * Fall back to getting the download details.
     */
    protected open suspend fun fallbackDownloadDetails() =
        super.getDownloadDetails(null, null)?.apply { cachedDownloadDetails = this }

    /**
     * Delete all files in cache that are not the recently downloaded file.
     */
    override suspend fun cleanUpPostDownload(downloadedFile: File) = withContext(Dispatchers.IO) {
        getCacheFolder()
            .listFiles()
            ?.filter { it != downloadedFile && calculateHash(it, defaultModelHashAlgorithm) != defaultModelHash }
            ?.sortedByDescending { it.lastModified() }
            ?.filterIndexed { index, file ->
                file.lastModified().asEpochMillisecondsClockMark()
                    .elapsedSince() > CACHE_MODEL_TIME || index > CACHE_MODEL_MAX_COUNT
            }
            ?.forEach { it.delete() }
            .let { }
    }

    /**
     * If a file in the cache directory matches the provided [hash], return it.
     */
    private suspend fun getMatchingFile(hash: String, hashAlgorithm: String): File? =
        withContext(Dispatchers.IO) {
            getCacheFolder()
                .listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.firstOrNull { calculateHash(it, hashAlgorithm) == hash }
        }

    /**
     * Get the highest model version, or most recently created file in the cache folder. Return null
     * if no files in cache
     */
    private suspend fun getLatestFile(): File? = withContext(Dispatchers.IO) {
        val files = getCacheFolder()
            .listFiles()
            ?.filter { it.exists() && it.length() > 0 }

        files
            ?.filter { it.name.startsWith("1.") }
            ?.mapNotNull { file -> ModelVersion.fromString(file.name)?.let { it to file } }
            ?.maxByOrNull { it.first }
            ?.second ?: files?.maxByOrNull { it.lastModified() }
    }

    data class ModelVersion(
        val versioningVersion: Int,
        val frameworkVersion: Int,
        val modelNumber: Int,
        val quantization: Int,
    ) : Comparable<ModelVersion> {
        override fun compareTo(other: ModelVersion): Int {
            val versioningDiff = versioningVersion.compareTo(other.versioningVersion)
            val frameworkDiff = frameworkVersion.compareTo(other.frameworkVersion)
            val modelDiff = modelNumber.compareTo(other.modelNumber)

            return when {
                versioningDiff != 0 -> versioningDiff
                frameworkDiff != 0 -> frameworkDiff
                modelDiff != 0 -> modelDiff
                else -> 0
            }
        }

        companion object {
            fun fromString(modelVersion: String): ModelVersion? {
                val components = modelVersion.split("\\.").mapNotNull {
                    try { it.toInt() } catch (t: Throwable) { null }
                }

                if (components.size != 4) {
                    return null
                }

                return ModelVersion(
                    components[0],
                    components[1],
                    components[2],
                    components[3],
                )
            }
        }
    }

    /**
     * Ensure that the local folder exists and get it.
     */
    private suspend fun ensureLocalFolder(folderName: String): File = withContext(Dispatchers.IO) {
        val localFolder = File(context.cacheDir, folderName)
        if (localFolder.exists() && !localFolder.isDirectory) {
            localFolder.delete()
        }
        if (!localFolder.exists()) {
            localFolder.mkdirs()
        }
        localFolder
    }

    /**
     * Force re-download of models by clearing the cache.
     */
    override suspend fun clearCache() = withContext(Dispatchers.IO) {
        getCacheFolder().deleteRecursively()
        getCacheFolder().mkdirs()

        StorageFactory
            .getStorageInstance(context, PURPOSE_MODEL_UPGRADE)
            .clear()
    }.let { }
}

/**
 * A [WebFetcher] that queries Bouncer servers for updated data. If a new version is found, download it. If the data
 * details match what is cached, return the cached version instead.
 */
abstract class UpdatingResourceFetcher(context: Context) : UpdatingModelWebFetcher(context) {
    protected abstract val resource: Int
    protected abstract val resourceModelVersion: String
    protected abstract val resourceModelHash: String
    protected abstract val resourceModelHashAlgorithm: String

    override val defaultModelFileName: String = ""
    override val defaultModelVersion: String by lazy { resourceModelVersion }
    override val defaultModelHash: String by lazy { resourceModelHash }
    override val defaultModelHashAlgorithm: String by lazy { resourceModelHashAlgorithm }

    override suspend fun tryFetchLatestCachedData() = super.tryFetchLatestCachedData().run {
        when (this) {
            is FetchedModelFileMeta -> if (modelFile == null) fetchModelFromResource() else this
            is FetchedModelResourceMeta -> this
        }
    }

    override suspend fun tryFetchMatchingCachedFile(hash: String, hashAlgorithm: String): FetchedModelMeta =
        if (hash == defaultModelHash && hashAlgorithm == defaultModelHashAlgorithm) {
            fetchModelFromResource()
        } else {
            super.tryFetchMatchingCachedFile(hash, hashAlgorithm)
        }

    override suspend fun fallbackDownloadDetails(): DownloadDetails? = DownloadDetails(
        url = URL("https://localhost"),
        hash = resourceModelHash,
        hashAlgorithm = resourceModelHashAlgorithm,
        modelVersion = resourceModelVersion,
    )

    private fun fetchModelFromResource(): FetchedModelMeta =
        FetchedModelResourceMeta(
            modelVersion = resourceModelVersion,
            resourceId = resource,
            hash = resourceModelHash,
            hashAlgorithm = resourceModelHashAlgorithm,
        )
}

/**
 * Determine if a [File] matches the expected [hash].
 */
private suspend fun fileMatchesHash(localFile: File, hash: String, hashAlgorithm: String) = try {
    hash == calculateHash(localFile, hashAlgorithm)
} catch (t: Throwable) {
    false
}

/**
 * Download a file from a given [url] and ensure that it matches the expected [hash].
 */
@Throws(IOException::class, FileCreationException::class, NoSuchAlgorithmException::class, HashMismatchException::class)
private suspend fun downloadAndVerify(
    context: Context,
    url: URL,
    outputFile: File,
    hash: String,
    hashAlgorithm: String
) {
    downloadFile(context, url, outputFile)
    val calculatedHash = calculateHash(outputFile, hashAlgorithm)

    if (hash != calculatedHash) {
        withContext(Dispatchers.IO) { outputFile.delete() }
        throw HashMismatchException(hashAlgorithm, hash, calculatedHash)
    }
}

/**
 * Calculate the hash of a file using the [hashAlgorithm].
 */
@Throws(IOException::class, NoSuchAlgorithmException::class)
private suspend fun calculateHash(file: File, hashAlgorithm: String): String? = withContext(Dispatchers.IO) {
    if (file.exists()) {
        val digest = MessageDigest.getInstance(hashAlgorithm)
        FileInputStream(file).use { digest.update(it.readBytes()) }
        digest.digest().joinToString("") { "%02x".format(it) }
    } else {
        null
    }
}

/**
 * Download a file from the provided [url] into the provided [outputFile].
 */
@Throws(IOException::class, FileCreationException::class)
private suspend fun downloadFile(context: Context, url: URL, outputFile: File) = withContext(Dispatchers.IO) {
    if (outputFile.exists()) {
        outputFile.delete()
    }

    downloadFileWithRetries(context, url, outputFile)
}

/**
 * A file does not match the expected hash value.
 */
class HashMismatchException(val algorithm: String, val expected: String, val actual: String?) :
    Exception("Invalid hash result for algorithm '$algorithm'. Expected '$expected' but got '$actual'") {
    override fun toString() = "HashMismatchException(algorithm='$algorithm', expected='$expected', actual='$actual')"
}
