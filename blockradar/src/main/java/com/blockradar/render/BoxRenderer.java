package com.blockradar.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClientLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import com.blockradar.BlockRadar;
import com.blockradar.config.BlockRadarConfig;
import com.blockradar.config.HighlightEntry;

/**
 * Scans blocks around the player on a timer, then draws a translucent box over every match.
 * <p>
 * This is adapted directly from the "Rendering in the World" guide for 26.1.2
 * (docs.fabricmc.net/26.1.2/develop/rendering/world) - a custom RenderPipeline based on
 * DEBUG_FILLED_SNIPPET with depth testing removed, so highlights are visible through walls
 * (handy for spotting ores/blocks behind terrain). Set "seeThroughWalls": false in the config
 * to use the plain RenderPipelines.DEBUG_FILLED_BOX pipeline instead, which respects depth.
 */
public final class BoxRenderer {
	private static BoxRenderer instance;

	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath(BlockRadar.MOD_ID, "pipeline/highlight_box"))
					.withDepthStencilState(Optional.empty())
					.build()
	);

	private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private BufferBuilder buffer;
	private MappableRingBuffer vertexBuffer;

	// Filled in by the periodic scan (see BlockRadar's tick handler), read by extraction.
	private volatile List<HighlightedBox> pendingBoxes = List.of();
	private volatile boolean seeThroughWalls = true;
	// Snapshot actually used for this frame's draw - copied during extraction, per the docs'
	// "render state must be immutable and fast to create" guidance.
	private List<HighlightedBox> frameBoxes = List.of();
	private boolean frameSeeThroughWalls = true;

	private record HighlightedBox(int x, int y, int z, float r, float g, float b, float a) {
	}

	public static BoxRenderer getInstance() {
		return instance;
	}

	public static void init() {
		instance = new BoxRenderer();
		LevelRenderEvents.END_EXTRACTION.register(instance::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(instance::renderAndDraw);
	}

	/** Called from a client tick handler every rescanIntervalTicks - NOT every frame. */
	public void rescan(ClientLevel level, Vec3 playerPos, BlockRadarConfig config) {
		seeThroughWalls = config.seeThroughWalls;

		if (!config.enabled || config.highlights.isEmpty()) {
			pendingBoxes = List.of();
			return;
		}

		List<HighlightedBox> found = new ArrayList<>();
		int px = (int) Math.floor(playerPos.x);
		int py = (int) Math.floor(playerPos.y);
		int pz = (int) Math.floor(playerPos.z);

		int xMin = Math.min(config.xMin, config.xMax);
		int xMax = Math.max(config.xMin, config.xMax);
		int zMin = Math.min(config.zMin, config.zMax);
		int zMax = Math.max(config.zMin, config.zMax);
		int yRadius = Math.max(0, config.yRadius);

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int dx = xMin; dx <= xMax; dx++) {
			for (int dz = zMin; dz <= zMax; dz++) {
				for (int dy = -yRadius; dy <= yRadius; dy++) {
					int x = px + dx;
					int y = py + dy;
					int z = pz + dz;
					cursor.set(x, y, z);

					if (!level.isLoaded(cursor)) continue;

					BlockState state = level.getBlockState(cursor);
					if (state.isAir()) continue;

					Block block = state.getBlock();
					String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();

					for (HighlightEntry entry : config.highlights) {
						if (entry.blockId.equalsIgnoreCase(id)) {
							found.add(new HighlightedBox(x, y, z, entry.red(), entry.green(), entry.blue(), entry.alpha()));
							break;
						}
					}
				}
			}
		}

		pendingBoxes = found;
	}

	private void extract(LevelExtractionContext context) {
		frameBoxes = pendingBoxes;
		frameSeeThroughWalls = seeThroughWalls;
	}

	private void renderAndDraw(LevelRenderContext context) {
		if (frameBoxes.isEmpty()) return;

		RenderPipeline pipeline = frameSeeThroughWalls ? FILLED_THROUGH_WALLS : RenderPipelines.DEBUG_FILLED_BOX;
		renderBoxes(context, pipeline);
		drawFilledThroughWalls(Minecraft.getInstance(), pipeline);
	}

	private void renderBoxes(LevelRenderContext context, RenderPipeline pipeline) {
		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		if (this.buffer == null) {
			this.buffer = new BufferBuilder(ALLOCATOR, pipeline.getVertexFormatMode(), pipeline.getVertexFormat());
		}

		Matrix4fc pose = matrices.last().pose();
		// Small inset so the highlight doesn't z-fight with the block's own faces.
		float pad = 0.002f;

		for (HighlightedBox box : frameBoxes) {
			renderFilledBox(pose, this.buffer,
					box.x() - pad, box.y() - pad, box.z() - pad,
					box.x() + 1 + pad, box.y() + 1 + pad, box.z() + 1 + pad,
					box.r(), box.g(), box.b(), box.a());
		}

		matrices.popPose();
	}

	private void renderFilledBox(Matrix4fc positionMatrix, BufferBuilder buffer,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
			float red, float green, float blue, float alpha) {
		// Front Face
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);

		// Back face
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);

		// Left face
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

		// Right face
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);

		// Top face
		buffer.addVertex(positionMatrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, maxY, minZ).setColor(red, green, blue, alpha);

		// Bottom face
		buffer.addVertex(positionMatrix, minX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
		buffer.addVertex(positionMatrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
	}

	private void drawFilledThroughWalls(Minecraft client, RenderPipeline pipeline) {
		MeshData builtBuffer = this.buffer.buildOrThrow();
		MeshData.DrawState drawParameters = builtBuffer.drawState();
		VertexFormat format = drawParameters.format();

		GpuBuffer vertices = this.upload(drawParameters, format, builtBuffer);

		draw(client, pipeline, builtBuffer, drawParameters, vertices, format);

		this.vertexBuffer.rotate();
		this.buffer = null;
	}

	private GpuBuffer upload(MeshData.DrawState drawParameters, VertexFormat format, MeshData builtBuffer) {
		int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();

		if (this.vertexBuffer == null || this.vertexBuffer.size() < vertexBufferSize) {
			if (this.vertexBuffer != null) {
				this.vertexBuffer.close();
			}
			this.vertexBuffer = new MappableRingBuffer(() -> BlockRadar.MOD_ID + " highlight box buffer",
					GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, vertexBufferSize);
		}

		CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();

		try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
				this.vertexBuffer.currentBuffer().slice(0, builtBuffer.vertexBuffer().remaining()), false, true)) {
			MemoryUtil.memCopy(builtBuffer.vertexBuffer(), mappedView.data());
		}

		return this.vertexBuffer.currentBuffer();
	}

	private static void draw(Minecraft client, RenderPipeline pipeline, MeshData builtBuffer,
			MeshData.DrawState drawParameters, GpuBuffer vertices, VertexFormat format) {
		GpuBuffer indices;
		VertexFormat.IndexType indexType;

		if (pipeline.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
			builtBuffer.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
			indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(builtBuffer.indexBuffer());
			indexType = builtBuffer.drawState().indexType();
		} else {
			RenderSystem.AutoStorageIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(pipeline.getVertexFormatMode());
			indices = shapeIndexBuffer.getBuffer(drawParameters.indexCount());
			indexType = shapeIndexBuffer.type();
		}

		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		try (RenderPass renderPass = RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass(() -> BlockRadar.MOD_ID + " highlight box rendering",
						client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
						client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
			renderPass.setPipeline(pipeline);

			RenderSystem.bindDefaultUniforms(renderPass);
			renderPass.setUniform("DynamicTransforms", dynamicTransforms);

			renderPass.setVertexBuffer(0, vertices);
			renderPass.setIndexBuffer(indices, indexType);

			renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
		}

		builtBuffer.close();
	}

	/** Called from GameRendererMixin on GameRenderer#close. */
	public void close() {
		ALLOCATOR.close();
		if (this.vertexBuffer != null) {
			this.vertexBuffer.close();
			this.vertexBuffer = null;
		}
	}
}
