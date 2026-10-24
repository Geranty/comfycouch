package sh.hnet.comfychair.util

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.model.LoraSelection
import java.io.File

/**
 * Tests für das Einfügen von LoRA-Nodes in Workflows.
 *
 * Ein ComfyUI-Workflow im API-Format ist ein JSON-Objekt aus Nodes.
 * Verbindungen sind Arrays der Form ["quellNodeId", ausgangsIndex].
 * Beim Einfügen einer LoRA muss die Kette so umgehängt werden:
 *
 *   vorher:  Loader ──model──▶ KSampler
 *   nachher: Loader ──model──▶ LoRA ──model──▶ KSampler
 */
class LoraInjectionUtilsTest {

    private fun ref(node: String, index: Int) = JSONArray().put(node).put(index)

    /** Minimaler Checkpoint-Workflow: Loader(4) → KSampler(3), CLIP(4,1) → TextEncode(6). */
    private fun checkpointWorkflow(): String = JSONObject().put(
        "nodes", JSONObject()
            .put("4", JSONObject().put("class_type", "CheckpointLoaderSimple")
                .put("inputs", JSONObject().put("ckpt_name", "model.safetensors")))
            .put("6", JSONObject().put("class_type", "CLIPTextEncode")
                .put("inputs", JSONObject().put("clip", ref("4", 1)).put("text", "a cat")))
            .put("3", JSONObject().put("class_type", "KSampler")
                .put("inputs", JSONObject().put("model", ref("4", 0)).put("positive", ref("6", 0))))
    ).toString()

    private fun nodes(json: String) = JSONObject(json).getJSONObject("nodes")

    private fun modelRef(nodes: JSONObject, id: String): Pair<String, Int> {
        val arr = nodes.getJSONObject(id).getJSONObject("inputs").getJSONArray("model")
        return arr.getString(0) to arr.getInt(1)
    }

    @Test
    fun emptyChainLeavesWorkflowUntouched() {
        val wf = checkpointWorkflow()
        assertEquals(wf, LoraInjectionUtils.injectLoraChain(wf, emptyList(), WorkflowType.TTI))
    }

    @Test
    fun singleLoraIsInsertedBetweenLoaderAndSampler() {
        val result = nodes(LoraInjectionUtils.injectLoraChain(
            checkpointWorkflow(), listOf(LoraSelection("style.safetensors", 0.8f)), WorkflowType.TTI
        ))

        // Neue Node-IDs beginnen bei 100, damit sie nicht mit vorhandenen kollidieren
        val lora = result.getJSONObject("100")
        assertEquals("LoraLoaderModelOnly", lora.getString("class_type"))
        assertEquals("style.safetensors", lora.getJSONObject("inputs").getString("lora_name"))
        assertEquals(0.8, lora.getJSONObject("inputs").getDouble("strength_model"), 1e-6)

        assertEquals("4" to 0, modelRef(result, "100"))   // LoRA hängt am Loader
        assertEquals("100" to 0, modelRef(result, "3"))   // Sampler hängt an der LoRA
    }

    @Test
    fun chainOfLorasIsLinkedInOrder() {
        val result = nodes(LoraInjectionUtils.injectLoraChain(
            checkpointWorkflow(),
            listOf(LoraSelection("a.safetensors"), LoraSelection("b.safetensors"), LoraSelection("c.safetensors")),
            WorkflowType.TTI
        ))

        assertEquals("4" to 0, modelRef(result, "100"))
        assertEquals("100" to 0, modelRef(result, "101"))
        assertEquals("101" to 0, modelRef(result, "102"))
        assertEquals("102" to 0, modelRef(result, "3"))
    }

    @Test
    fun clipOutputIsNotRewired() {
        // Nur der MODEL-Ausgang (Index 0) darf umgehängt werden, CLIP (Index 1) nicht
        val result = nodes(LoraInjectionUtils.injectLoraChain(
            checkpointWorkflow(), listOf(LoraSelection("a.safetensors")), WorkflowType.TTI
        ))
        val clip = result.getJSONObject("6").getJSONObject("inputs").getJSONArray("clip")
        assertEquals("4", clip.getString(0))
        assertEquals(1, clip.getInt(1))
    }

    @Test
    fun brokenJsonIsReturnedUnchanged() {
        val broken = "{ not json"
        assertEquals(broken, LoraInjectionUtils.injectLoraChain(broken, listOf(LoraSelection("a")), WorkflowType.TTI))
    }

    /**
     * Prüft alle mitgelieferten Bild-Workflows: Nach dem Einfügen darf nur noch
     * die LoRA direkt am MODEL-Ausgang des Loaders hängen.
     */
    @Test
    fun bundledImageWorkflowsAreRewiredCompletely() {
        val files = File("src/main/res/raw").listFiles { f ->
            f.name.endsWith(".json") && (f.name.startsWith("tti_") || f.name.startsWith("iti_"))
        }
        assertNotNull("Workflow-Ordner nicht gefunden", files)
        assertTrue("Keine Workflows gefunden", files!!.isNotEmpty())

        for (file in files) {
            val original = file.readText()
            val loaderId = nodes(original).let { n ->
                n.keys().asSequence().firstOrNull { n.getJSONObject(it).optString("class_type") == "CheckpointLoaderSimple" }
                    ?: n.keys().asSequence().first { n.getJSONObject(it).optString("class_type") == "UNETLoader" }
            }

            val result = nodes(LoraInjectionUtils.injectLoraChain(
                original, listOf(LoraSelection("x.safetensors")), WorkflowType.TTI
            ))

            // Lightning-Workflows bringen schon eine eigene LoRA mit, daher über den Namen suchen
            val loraId = result.keys().asSequence().single {
                result.getJSONObject(it).optJSONObject("inputs")?.optString("lora_name") == "x.safetensors"
            }

            for (id in result.keys()) {
                val inputs = result.getJSONObject(id).optJSONObject("inputs") ?: continue
                for (key in inputs.keys()) {
                    val v = inputs.opt(key)
                    if (v is JSONArray && v.length() >= 2 && v.optString(0) == loaderId && v.optInt(1) == 0) {
                        assertEquals("${file.name}: Node $id.$key hängt noch direkt am Loader", loraId, id)
                    }
                }
            }
        }
    }

    @Test
    fun videoLorasAreAppendedAfterHighNoiseLora() {
        val original = File("src/main/res/raw/ttv_wan22_lightning.json").readText()
        val result = nodes(LoraInjectionUtils.injectAdditionalVideoLoras(
            original, listOf(LoraSelection("extra.safetensors")), isHighNoise = true
        ))

        // Kette High-Noise: UNET(3) → LoRA(8) → neue LoRA → ModelSampling(10) → KSampler(12, start_at_step 0)
        val newId = result.keys().asSequence().single {
            result.getJSONObject(it).optString("class_type") == "LoraLoaderModelOnly" && it !in setOf("8", "9")
        }
        assertEquals("8" to 0, modelRef(result, newId))
        assertEquals(newId to 0, modelRef(result, "10"))
        // Low-Noise-Pfad bleibt unverändert
        assertEquals("9" to 0, modelRef(result, "11"))
    }

    @Test
    fun loraSelectionJsonRoundTrip() {
        val chain = listOf(LoraSelection("a.safetensors", 0.5f), LoraSelection("b.safetensors", 1.5f))
        assertEquals(chain, LoraSelection.fromJsonString(LoraSelection.toJsonString(chain)))
        // Einträge ohne Namen werden verworfen, kaputtes JSON ergibt eine leere Liste
        assertEquals(1, LoraSelection.fromJsonString("""[{"name":""},{"name":"c"}]""").size)
        assertEquals(emptyList<LoraSelection>(), LoraSelection.fromJsonString("kaputt"))
    }
}
