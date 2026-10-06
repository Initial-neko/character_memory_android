package stagea

import io.github.psd2live.core.*
import io.github.psd2live.ui.CanvasViewport
import io.github.psd2live.ui.RigCanvasSupport
import org.umamo.runtime.model.ParameterId
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    require(args.size >= 2) { "PSD and output paths are required" }
    val input = Path.of(args[0])
    val output = Path.of(args[1])
    require(!Files.exists(output)) { "Use a fresh output directory" }
    val color = args.getOrNull(2)?.takeIf { it.isNotEmpty() }
    require(color == null || color.matches(Regex("#[0-9a-fA-F]{6}"))) { "Use #RRGGBB" }
    val config = if (color == null) PipelineConfig()
        else PipelineConfig(mouthColor = color.substring(1).toInt(16))
    val listener = ProgressListener { stage, fraction -> println("${(fraction * 100).toInt()}% $stage") }
    val result = PSD2LivePipeline().run(input, output, config, listener)
    result.exportedFiles.forEach { println("EXPORTED ${it.path.fileName} ${it.bytes}") }
    result.warnings.forEach { println("WARNING $it") }
    val model = result.previewModel
    val parameters = buildJsonArray {
        model.rig.puppet.parameters.forEach { p -> add(buildJsonObject {
            put("id", p.id.raw); put("min", p.min); put("max", p.max)
        }) }
    }
    Files.writeString(output.resolve("parameters-check.json"), parameters.toString())
    val poses = linkedMapOf(
        "neutral" to mapOf("ParamMouthOpenY" to 0f),
        "mouth-half" to mapOf("ParamMouthOpenY" to 0.5f),
        "mouth-open" to mapOf("ParamMouthOpenY" to 1f),
        "blink" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f),
        "half-blink" to mapOf("ParamEyeLOpen" to 0.5f, "ParamEyeROpen" to 0.5f),
        "head-left" to mapOf("ParamAngleX" to -30f),
        "head-right" to mapOf("ParamAngleX" to 30f)
    )
    poses.forEach { (name, values) ->
        val image = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            val geometry = RigCanvasSupport.evaluate(model, values.mapKeys { ParameterId(it.key) })
            RigCanvasSupport.paintTexturedRig(graphics, model, geometry,
                CanvasViewport(1.0, 0.0, 0.0, 1024f, 1024f))
        } finally { graphics.dispose() }
        check(ImageIO.write(image, "png", output.resolve("preview-$name.png").toFile()))
    }
    println("SKILL_EXPORT_COMPLETE")
}
