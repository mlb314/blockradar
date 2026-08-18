package com.blockradar.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;

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

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import com.blockradar.BlockRadar;
import com.blockradar.config.BlockRadarConfig;
import com.blockradar.config.HighlightEntry;
import com.blockradar.structure.RelativeBlock;
import com.blockradar.structure.StructureManager;
import com.blockradar.structure.StructureTemplate;

/**
 * Scans a fixed world-space box (set in the config, NOT relative to the player) on a timer,
 * then draws a translucent box over every matching block found inside it, plus a floating
 * "name (distance)" waypoint label for each structure match.
 * <p>
 * Scanning is done per 16x16 chunk column (Minecraft's native grid), cached in {@link #chunkCache}.
 * A chunk is scanned AT MOST ONCE per server once it has been successfully scanned while loaded.
 * Unloaded chunks are left out of the cache so they can be scanned later when they load.
 * New chunks are scanned in small batches (controlled by config.maxChunksPerRescan)
 * prioritised by distance to the player, preventing client freezes on large ranges.
 * <p>
 * The GPU drawing part is adapted directly from the "Rendering in the World" guide for 26.1.2
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
	private static final int CHUNK_SIZE = 16;
	private static final int[] ROTATIONS = {0, 90, 180, 270};

	private BufferBuilder buffer;
	private MappableRingBuffer vertexBuffer;

	// Per-chunk-column scan cache for the CURRENT server/world. Key is ChunkPos.pack(chunkX, chunkZ).
	// Not final - swapped out wholesale by onServerChanged() when the active server changes.
	private Map<Long, List<HighlightedBox>> chunkCache = new HashMap<>();
	// Signature of the settings the current chunkCache was built against (range + highlighted
	// blocks + enabled structure templates). If this changes, the cache is invalidated.
	private int cachedSignature = 0;

	// One saved cache per distinct chat-detected server "key" (see BlockRadar#serverChangeChatTrigger
	// handling / onServerChanged below). Lets you hop between servers you've already scanned
	// without losing that work, while a genuinely new server starts from a clean slate.
	private final Map<String, ServerCache> perServerCache = new HashMap<>();
	private String currentServerKey = null;

	private record ServerCache(Map<Long, List<HighlightedBox>> chunks, int signature) {
	}

	// Filled in by the periodic scan (see BlockRadar's tick handler), read by extraction.
	private volatile List<HighlightedBox> pendingBoxes = List.of();
	private volatile boolean seeThroughWalls = true;
	// Snapshot actually used for this frame's draw - copied during extraction, per the docs'
	// "render state must be immutable and fast to create" guidance.
	private List<HighlightedBox> frameBoxes = List.of();
	private boolean frameSeeThroughWalls = true;

	// Combined view*projection matrix from the most recent world-render frame, used to project
	// waypoint world positions onto the 2D HUD. Updated every frame regardless of whether there
	// are any boxes to draw, so waypoints stay accurate even with box rendering skipped.
	private final Matrix4f frameViewProj = new Matrix4f();
	private boolean frameViewProjValid = false;

	/** A highlighted region: a single block (sizeX=sizeY=sizeZ=1) or a structure's bounding box.
	 *  label is null for plain block highlights, and the template name for structure matches -
	 *  only labeled boxes get a waypoint drawn on the HUD. */
	private record HighlightedBox(int x, int y, int z, int sizeX, int sizeY, int sizeZ,
			float r, float g, float b, float a, String label) {
		double centerX() { return x + sizeX / 2.0; }
		double centerY() { return y + sizeY / 2.0; }
		double centerZ() { return z + sizeZ / 2.0; }
	}

	public static BoxRenderer getInstance() {
		return instance;
	}

	public static void init() {
		instance = new BoxRenderer();
		LevelRenderEvents.END_EXTRACTION.register(instance::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(instance::renderAndDraw);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BlockRadar.MOD_ID, "waypoints"), instance::renderWaypoints);
	}

	/**
	 * Called from BlockRadar's chat listener when a message matching config.serverChangeChatTrigger
	 * is received. rawMessage is the FULL trimmed chat line, used as-is as the cache key: a
	 * server you haven't seen this key for before starts with a completely empty (all-new)
	 * cache, while a repeat key restores whatever was cached for it before.
	 */
	public void onServerChanged(String rawMessage) {
		if (rawMessage.equals(currentServerKey)) return; // same server message repeated - no-op

		if (currentServerKey != null) {
			perServerCache.put(currentServerKey, new ServerCache(chunkCache, cachedSignature));
		}
		currentServerKey = rawMessage;

		ServerCache stored = perServerCache.get(rawMessage);
		if (stored != null) {
			chunkCache = stored.chunks();
			cachedSignature = stored.signature();
		} else {
			chunkCache = new HashMap<>();
			cachedSignature = 0;
		}
	}

	/**
	 * Clears the current server's chunk cache so every required chunk will be
	 * re-scanned from scratch on subsequent rescan ticks.
	 * Call this from the config screen "Reset Chunks" button.
	 */
	public void resetCurrentChunks() {
		chunkCache.clear();
		cachedSignature = 0;
		pendingBoxes = List.of();
		System.out.println("[blockradar] Current server chunk cache reset – will rescan.");
	}

	/** Called from a client tick handler every rescanIntervalTicks - NOT every frame. */
	public void rescan(ClientLevel level, BlockRadarConfig config) {
		seeThroughWalls = config.seeThroughWalls;

		List<StructureTemplate> structures = StructureManager.getEnabled();

		if (!config.enabled || (config.highlights.isEmpty() && structures.isEmpty())) {
			pendingBoxes = List.of();
			chunkCache.clear();
			return;
		}

		int signature = computeSignature(config, structures);
		if (signature != cachedSignature) {
			// Range, highlighted-block, or structure-template settings changed since the cache
			// was built - old per-chunk results no longer mean anything, so start over.
			chunkCache.clear();
			cachedSignature = signature;
		}

		int xMin = Math.min(config.xMin, config.xMax);
		int xMax = Math.max(config.xMin, config.xMax);
		int zMin = Math.min(config.zMin, config.zMax);
		int zMax = Math.max(config.zMin, config.zMax);
		int yMin = Math.min(config.yMin, config.yMax);
		int yMax = Math.max(config.yMin, config.yMax);

		int chunkXMin = Math.floorDiv(xMin, CHUNK_SIZE);
		int chunkXMax = Math.floorDiv(xMax, CHUNK_SIZE);
		int chunkZMin = Math.floorDiv(zMin, CHUNK_SIZE);
		int chunkZMax = Math.floorDiv(zMax, CHUNK_SIZE);

		// Player position used for prioritising which chunks to scan first
		Minecraft client = Minecraft.getInstance();
		double playerX = client.player != null ? client.player.getX() : 0;
		double playerZ = client.player != null ? client.player.getZ() : 0;

		Set<Long> required = new HashSet<>();
		List<long[]> missing = new ArrayList<>();   // [chunkX, chunkZ, distSq]

		for (int cx = chunkXMin; cx <= chunkXMax; cx++) {
			for (int cz = chunkZMin; cz <= chunkZMax; cz++) {
				long key = ChunkPos.pack(cx, cz);
				required.add(key);

				if (!chunkCache.containsKey(key)) {
					// Only consider it "missing" if the chunk is currently loaded.
					// Unloaded chunks stay out of the cache so they can be scanned later.
					if (level.hasChunk(cx, cz)) {
						double dx = (cx * 16 + 8) - playerX;
						double dz = (cz * 16 + 8) - playerZ;
						missing.add(new long[]{cx, cz, (long) (dx * dx + dz * dz)});
					}
				}
			}
		}

		// Drop cached chunks that fell out of the configured box - keeps memory bounded and
		// means re-including an area later (by widening the range) counts as "new" again.
		chunkCache.keySet().removeIf(key -> !required.contains(key));

		// Sort missing chunks by distance to player (closest first) and only process a few
		missing.sort((a, b) -> Long.compare(a[2], b[2]));

		int maxPerTick = Math.max(1, Math.min(16, config.maxChunksPerRescan));
		int scannedThisTick = 0;
		for (long[] entry : missing) {
			if (scannedThisTick >= maxPerTick) break;

			int cx = (int) entry[0];
			int cz = (int) entry[1];
			long key = ChunkPos.pack(cx, cz);

			// Double-check it is still loaded (very rare race)
			if (!level.hasChunk(cx, cz)) continue;

			List<HighlightedBox> result = scanChunk(level, cx, cz,
					xMin, xMax, zMin, zMax, yMin, yMax,
					config.highlights, structures);

			chunkCache.put(key, result);
			scannedThisTick++;
		}

		// Rebuild the combined list from everything we currently have cached
		List<HighlightedBox> combined = new ArrayList<>();
		for (Long key : required) {
			List<HighlightedBox> boxes = chunkCache.get(key);
			if (boxes != null) {
				combined.addAll(boxes);
			}
		}

		pendingBoxes = combined;
	}

	private List<HighlightedBox> scanChunk(ClientLevel level, int chunkX, int chunkZ,
			int xMin, int xMax, int zMin, int zMax, int yMin, int yMax,
			List<HighlightEntry> highlights, List<StructureTemplate> structures) {
		List<HighlightedBox> found = new ArrayList<>();

		// Fast path – if the whole chunk column is not loaded, return empty immediately
		if (!level.hasChunk(chunkX, chunkZ)) {
			return found;
		}

		// Intersect this chunk's block range with the configured X/Z box, so partial chunks
		// at the edge of the range don't scan blocks outside it.
		int xStart = Math.max(chunkX * CHUNK_SIZE, xMin);
		int xEnd = Math.min(chunkX * CHUNK_SIZE + CHUNK_SIZE - 1, xMax);
		int zStart = Math.max(chunkZ * CHUNK_SIZE, zMin);
		int zEnd = Math.min(chunkZ * CHUNK_SIZE + CHUNK_SIZE - 1, zMax);

		if (xStart > xEnd || zStart > zEnd) return found;

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int x = xStart; x <= xEnd; x++) {
			for (int z = zStart; z <= zEnd; z++) {
				for (int y = yMin; y <= yMax; y++) {
					cursor.set(x, y, z);

					if (!level.isLoaded(cursor)) continue;

					BlockState state = level.getBlockState(cursor);
					if (state.isAir()) continue;

					Block block = state.getBlock();
					String id = BuiltInRegistries.BLOCK.getKey(block).toString();

					for (HighlightEntry entry : highlights) {
						if (entry.blockId.equalsIgnoreCase(id)) {
							found.add(new HighlightedBox(x, y, z, 1, 1, 1, entry.red(), entry.green(), entry.blue(), entry.alpha(), null));
							break;
						}
					}

					// Structure detection: this block is only a candidate "anchor" if it matches
					// the FIRST block of a template - full verification only runs for that
					// narrow subset, so this stays cheap even with several templates loaded.
					// Every horizontal (Y-axis) rotation is tried, since a monument can be facing
					// any of the 4 cardinal directions; block TYPE matching is direction-agnostic
					// already (we compare block id only, not facing/state), so no per-block
					// rotation logic beyond repositioning is needed.
					for (StructureTemplate template : structures) {
						RelativeBlock anchor = template.anchor();
						if (!anchor.blockId.equalsIgnoreCase(id)) continue;

						for (int rotation : ROTATIONS) {
							int adx = rotateDx(anchor.dx, anchor.dz, rotation);
							int adz = rotateDz(anchor.dx, anchor.dz, rotation);
							int originX = x - adx;
							int originY = y - anchor.dy;
							int originZ = z - adz;

							double percent = matchPercent(level, originX, originY, originZ, template, rotation);
							if (percent >= template.matchThresholdPercent) {
								int[] bounds = rotatedBounds(template, rotation);
								found.add(new HighlightedBox(
										originX + bounds[0], originY + template.minDy, originZ + bounds[2],
										bounds[1] - bounds[0] + 1,
										template.maxDy - template.minDy + 1,
										bounds[3] - bounds[2] + 1,
										template.red(), template.green(), template.blue(), template.alpha(),
										template.name));
								break; // don't test further rotations once one matches at this anchor
							}
						}
					}
				}
			}
		}

		return found;
	}

	/** Percentage of a template's blocks that still match, ignoring positions in unloaded chunks. */
	private double matchPercent(ClientLevel level, int originX, int originY, int originZ, StructureTemplate template, int rotation) {
		int attempted = 0;
		int matched = 0;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (RelativeBlock rb : template.blocks) {
			int rdx = rotateDx(rb.dx, rb.dz, rotation);
			int rdz = rotateDz(rb.dx, rb.dz, rotation);
			cursor.set(originX + rdx, originY + rb.dy, originZ + rdz);
			if (!level.isLoaded(cursor)) continue;

			attempted++;
			BlockState state = level.getBlockState(cursor);
			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (rb.blockId.equalsIgnoreCase(id)) matched++;
		}

		return attempted == 0 ? 0.0 : (matched * 100.0) / attempted;
	}

	/** Rotates a relative (dx, dz) offset around the Y axis by 0/90/180/270 degrees. */
	private static int rotateDx(int dx, int dz, int rotationDegrees) {
		return switch (rotationDegrees) {
			case 90 -> -dz;
			case 180 -> -dx;
			case 270 -> dz;
			default -> dx;
		};
	}

	private static int rotateDz(int dx, int dz, int rotationDegrees) {
		return switch (rotationDegrees) {
			case 90 -> dx;
			case 180 -> -dz;
			case 270 -> -dx;
			default -> dz;
		};
	}

	/** Bounding box (in rotated dx/dz space) of a template after applying the given rotation. */
	private static int[] rotatedBounds(StructureTemplate template, int rotationDegrees) {
		int[] cornersDx = {template.minDx, template.minDx, template.maxDx, template.maxDx};
		int[] cornersDz = {template.minDz, template.maxDz, template.minDz, template.maxDz};

		int minRDx = Integer.MAX_VALUE, maxRDx = Integer.MIN_VALUE;
		int minRDz = Integer.MAX_VALUE, maxRDz = Integer.MIN_VALUE;

		for (int i = 0; i < 4; i++) {
			int rdx = rotateDx(cornersDx[i], cornersDz[i], rotationDegrees);
			int rdz = rotateDz(cornersDx[i], cornersDz[i], rotationDegrees);
			minRDx = Math.min(minRDx, rdx);
			maxRDx = Math.max(maxRDx, rdx);
			minRDz = Math.min(minRDz, rdz);
			maxRDz = Math.max(maxRDz, rdz);
		}

		return new int[]{minRDx, maxRDx, minRDz, maxRDz};
	}

	private static int computeSignature(BlockRadarConfig config, List<StructureTemplate> structures) {
		StringBuilder sb = new StringBuilder();
		sb.append(config.xMin).append(',').append(config.xMax).append(',')
				.append(config.zMin).append(',').append(config.zMax).append(',')
				.append(config.yMin).append(',').append(config.yMax);
		for (HighlightEntry entry : config.highlights) {
			sb.append('|').append(entry.blockId).append(':').append(entry.color);
		}
		for (StructureTemplate template : structures) {
			sb.append("|s:").append(template.name).append(':').append(template.matchThresholdPercent).append(':').append(template.color);
		}
		return sb.toString().hashCode();
	}

	private void extract(LevelExtractionContext context) {
		frameBoxes = pendingBoxes;
		frameSeeThroughWalls = seeThroughWalls;
	}

	private void renderAndDraw(LevelRenderContext context) {
		captureViewProjMatrix(context);

		if (frameBoxes.isEmpty()) return;

		RenderPipeline pipeline = frameSeeThroughWalls ? FILLED_THROUGH_WALLS : RenderPipelines.DEBUG_FILLED_BOX;
		renderBoxes(context, pipeline);
		drawFilledThroughWalls(Minecraft.getInstance(), pipeline);
	}

	/** Stores this frame's combined view*projection matrix for waypoint HUD projection.
	 *  RenderSystem no longer exposes a CPU-readable projection matrix as of 1.21.6+ (it moved
	 *  to a GPU-side buffer), so the projection half is computed here directly from the current
	 *  FOV/aspect ratio with plain perspective-projection math instead of fetching a Mojang
	 *  matrix - this is a very close approximation (may drift slightly during effects like the
	 *  sprint FOV boost, which isn't accounted for), which is fine for label placement. */
	private void captureViewProjMatrix(LevelRenderContext context) {
		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		Minecraft client = Minecraft.getInstance();
		float fovDegrees = client.options.fov().get();
		float aspect = (float) client.getWindow().getWidth() / (float) client.getWindow().getHeight();
		Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspect, 0.05f, 4096f);

		frameViewProj.set(projection).mul(matrices.last().pose());
		frameViewProjValid = true;

		matrices.popPose();
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
					box.x() + box.sizeX() + pad, box.y() + box.sizeY() + pad, box.z() + box.sizeZ() + pad,
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

	/**
	 * Draws a "name (Nm)" label at the projected screen position of every structure match
	 * (plain block highlights don't get waypoints - there can be too many of those for labels
	 * to be readable). Uses the view*projection matrix captured during the last world-render
	 * frame to convert each structure's world-space center into a 2D screen position.
	 */
	private void renderWaypoints(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!frameViewProjValid) return;

		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (player == null) return;

		int guiWidth = client.getWindow().getGuiScaledWidth();
		int guiHeight = client.getWindow().getGuiScaledHeight();
		Vec3 playerPos = player.position();

		for (HighlightedBox box : frameBoxes) {
			if (box.label() == null) continue;

			Vector4f clip = new Vector4f((float) box.centerX(), (float) box.centerY(), (float) box.centerZ(), 1f);
			frameViewProj.transform(clip);

			if (clip.w() <= 0.01f) continue; // behind the camera

			float ndcX = clip.x() / clip.w();
			float ndcY = clip.y() / clip.w();
			if (ndcX < -1f || ndcX > 1f || ndcY < -1f || ndcY > 1f) continue; // off-screen

			int screenX = Math.round((ndcX * 0.5f + 0.5f) * guiWidth);
			int screenY = Math.round((1f - (ndcY * 0.5f + 0.5f)) * guiHeight);

			double distance = playerPos.distanceTo(new Vec3(box.centerX(), box.centerY(), box.centerZ()));
			String text = box.label() + " (" + Math.round(distance) + "m)";

			int textWidth = client.font.width(text);
			int color = 0xFF000000 | ((int) (box.r() * 255) << 16) | ((int) (box.g() * 255) << 8) | (int) (box.b() * 255);
			graphics.text(client.font, text, screenX - textWidth / 2, screenY, color, true);
		}
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
