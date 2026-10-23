package sh.hnet.comfychair.viewmodel

/**
 * ViewModel for the Text-to-Video screen.
 *
 * Fast alles steckt in [VideoGenerationViewModel], das sich Text-to-Video und
 * Image-to-Video teilen. Hier steht nur, was Text-to-Video anders macht.
 */
class TextToVideoViewModel : VideoGenerationViewModel(VideoMode.TEXT_TO_VIDEO) {

    companion object {
        const val OWNER_ID = "TEXT_TO_VIDEO"
    }

    /** Text-to-Video braucht kein Quellbild, der Workflow kann direkt gebaut werden. */
    fun prepareWorkflow(): String? = buildWorkflow(imageFilename = null)
}
