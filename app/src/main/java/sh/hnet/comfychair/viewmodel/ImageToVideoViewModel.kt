package sh.hnet.comfychair.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.R
import sh.hnet.comfychair.cache.MediaStateHolder
import sh.hnet.comfychair.connection.ConnectionFailure
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.util.UuidUtils
import kotlin.coroutines.suspendCoroutine

/**
 * ViewModel for the Image-to-Video screen.
 *
 * Der gemeinsame Teil mit Text-to-Video steckt in [VideoGenerationViewModel].
 * Hier steht nur das Quellbild: auswählen, merken, hochladen, Ansicht umschalten.
 */
class ImageToVideoViewModel : VideoGenerationViewModel(VideoMode.IMAGE_TO_VIDEO) {

    companion object {
        const val OWNER_ID = "IMAGE_TO_VIDEO"
    }

    override fun onPreferencesRestored() {
        loadSavedSourceImage()
    }

    override fun onStartListening(generationViewModel: GenerationViewModel) {
        // If generation is running for this screen, switch to preview mode
        val state = generationViewModel.generationState.value
        if (state.isGenerating && state.ownerId == OWNER_ID) {
            _uiState.value = _uiState.value.copy(viewMode = ImageToVideoViewMode.PREVIEW)
        }
    }

    override fun hasValidConfiguration(): Boolean {
        // Source image is required for Image-to-Video
        if (_uiState.value.sourceImage == null) {
            return false
        }
        return super.hasValidConfiguration()
    }

    private fun loadSavedSourceImage() {
        // Restore from cache (memory in memory-first mode, disk in disk-first mode)
        val sourceImage = MediaStateHolder.getBitmap(MediaStateHolder.MediaKey.ItvSource, applicationContext)
        if (sourceImage != null) {
            _uiState.value = _uiState.value.copy(sourceImage = sourceImage)
        }
    }

    // View mode
    fun onViewModeChange(mode: ImageToVideoViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
    }

    // Source image
    fun onSourceImageChange(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    // Store in cache (memory or disk based on mode)
                    MediaStateHolder.putBitmap(MediaStateHolder.MediaKey.ItvSource, bitmap, context)

                    _uiState.value = _uiState.value.copy(sourceImage = bitmap)
                }
            } catch (e: Exception) {
                _events.emit(VideoEvent.ShowToast(R.string.error_save_image))
            }
        }
    }

    /**
     * Upload source image to ComfyUI and prepare workflow
     */
    suspend fun prepareWorkflow(): String? {
        val client = comfyUIClient ?: return null
        val context = applicationContext ?: return null

        // Check connection before uploading
        val connected = suspendCoroutine { cont ->
            ConnectionManager.ensureConnection(context) { success ->
                cont.resumeWith(Result.success(success))
            }
        }
        if (!connected) return null  // Dialog already shown by ensureConnection

        val state = _uiState.value

        val sourceImage = state.sourceImage
        if (sourceImage == null) {
            _events.emit(VideoEvent.ShowToast(R.string.msg_no_source_image))
            return null
        }

        // Validate all fields before uploading anything
        if (state.widthError != null || state.heightError != null ||
            state.lengthError != null || state.fpsError != null) {
            return null
        }

        _uiState.update { it.copy(isUploading = true) }
        return try {
            // Convert bitmap to PNG byte array
            // Ensure we have ARGB_8888 format for proper PNG encoding
            val imageBytes = withContext(Dispatchers.IO) {
                val bitmapToUpload = if (sourceImage.config != Bitmap.Config.ARGB_8888) {
                    sourceImage.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    sourceImage
                }
                val outputStream = java.io.ByteArrayOutputStream()
                bitmapToUpload.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                if (bitmapToUpload !== sourceImage) {
                    bitmapToUpload.recycle()
                }
                outputStream.toByteArray()
            }

            // Upload to ComfyUI
            data class UploadResult(val filename: String?, val failureType: ConnectionFailure)
            val uploadResult: UploadResult = withContext(Dispatchers.IO) {
                suspendCoroutine { continuation ->
                    client.uploadImage(imageBytes, UuidUtils.generateUniqueUploadFilename("itv_source")) { success, filename, _, failureType ->
                        continuation.resumeWith(Result.success(UploadResult(if (success) filename else null, failureType)))
                    }
                }
            }

            if (uploadResult.filename == null) {
                // Check for stall or auth failure - show dialog instead of toast
                if (uploadResult.failureType == ConnectionFailure.STALLED ||
                    uploadResult.failureType == ConnectionFailure.AUTHENTICATION) {
                    applicationContext?.let { ctx ->
                        ConnectionManager.showConnectionAlert(ctx, uploadResult.failureType)
                    }
                } else {
                    _events.emit(VideoEvent.ShowToast(R.string.error_save_image))
                }
                return null
            }

            buildWorkflow(imageFilename = uploadResult.filename)
        } finally {
            _uiState.update { it.copy(isUploading = false) }
        }
    }
}
