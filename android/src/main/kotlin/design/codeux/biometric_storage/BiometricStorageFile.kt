package design.codeux.biometric_storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyProperties
import android.util.Log
import mu.KotlinLogging
import java.io.File
import java.io.IOException
import javax.crypto.Cipher

private val logger = KotlinLogging.logger {}

data class InitOptions(
    val authenticationValidityDurationSeconds: Int = -1,
    val authenticationRequired: Boolean = true,
    val androidBiometricOnly: Boolean = true
)

class BiometricStorageFile(
    context: Context,
    baseName: String,
    val options: InitOptions
) {

    companion object {
        /**
         * Name of directory inside private storage where all encrypted files are stored.
         */
        private const val DIRECTORY_NAME = "biometric_storage"
        private const val FILE_SUFFIX_V2 = ".v2.txt"
    }

    private val masterKeyName = "${baseName}_master_key"
    private val fileNameV2 = "$baseName$FILE_SUFFIX_V2"
    private val fileV2: File

    private val cryptographyManager = CryptographyManager {
        setUserAuthenticationRequired(options.authenticationRequired)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val useStrongBox = context.packageManager.hasSystemFeature(
                PackageManager.FEATURE_STRONGBOX_KEYSTORE
            )
            setIsStrongBoxBacked(useStrongBox)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (options.authenticationValidityDurationSeconds == -1) {
                setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            } else {
                setUserAuthenticationParameters(
                    options.authenticationValidityDurationSeconds,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG
                )
            }
        } else {
            @Suppress("DEPRECATION")
            setUserAuthenticationValidityDurationSeconds(options.authenticationValidityDurationSeconds)
        }
    }

    init {
        val baseDir = File(context.filesDir, DIRECTORY_NAME)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        fileV2 = File(baseDir, fileNameV2)

        Log.d("Andrew", "Initialized $this with $options")

        logger.trace { "Initialized $this with $options" }

        validateOptions()
    }

    private fun validateOptions() {
        if (options.authenticationValidityDurationSeconds == -1 && !options.androidBiometricOnly) {
            throw IllegalArgumentException("when authenticationValidityDurationSeconds is -1, androidBiometricOnly must be true")
        }
    }

    fun cipherForEncrypt() = cryptographyManager.getInitializedCipherForEncryption(masterKeyName)
    fun cipherForDecrypt(): Cipher? {
        if (fileV2.exists()) {
            return cryptographyManager.getInitializedCipherForDecryption(masterKeyName, fileV2)
        }
        Log.d("Andrew", "No file exists, no IV found. null cipher.")
        logger.debug { "No file exists, no IV found. null cipher." }
        return null
    }

    fun exists() = fileV2.exists()

    @Synchronized
    fun writeFile(cipher: Cipher?, content: String) {
        // cipher will be null if user does not need authentication or valid period is > -1
        val useCipher = cipher ?: cipherForEncrypt()
        try {
            val encrypted = cryptographyManager.encryptData(content, useCipher)
            fileV2.writeBytes(encrypted.encryptedPayload)
            Log.d("Andrew", "Successfully written ${encrypted.encryptedPayload.size} bytes.")
            logger.debug { "Successfully written ${encrypted.encryptedPayload.size} bytes." }

            return
        } catch (ex: IOException) {
            // Error occurred opening file for writing.
            Log.d("Andrew", "Error while writing encrypted file $fileV2")
            logger.error(ex) { "Error while writing encrypted file $fileV2" }
            throw ex
        }
    }

    @Synchronized
    fun readFile(cipher: Cipher?): String? {
        val useCipher = cipher ?: cipherForDecrypt()
        // if the file exists, there should *always* be a decryption key.
        if (useCipher != null && fileV2.exists()) {
            return try {
                val bytes = fileV2.readBytes()
                logger.debug { "read ${bytes.size}" }
                cryptographyManager.decryptData(bytes, useCipher)
            } catch (ex: IOException) {
                Log.d("Andrew", "Error while writing encrypted file $fileV2")
                logger.error(ex) { "Error while writing encrypted file $fileV2" }
                null
            }
        }

        logger.debug { "File $fileV2 does not exist. returning null." }
        return null

    }

    @Synchronized
    fun deleteFile(): Boolean {
        cryptographyManager.deleteKey(masterKeyName)
        return fileV2.delete()
    }

    override fun toString(): String {
        return "BiometricStorageFile(masterKeyName='$masterKeyName', fileName='$fileNameV2', file=$fileV2)"
    }

    fun dispose() {
        logger.trace { "dispose" }
    }

}
