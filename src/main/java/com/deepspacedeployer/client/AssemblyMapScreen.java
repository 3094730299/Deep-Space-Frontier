package com.deepspacedeployer.client;

import com.deepspacedeployer.Config;
import com.deepspacedeployer.DeepSpaceDeployer;
import com.deepspacedeployer.net.SetAssemblyTargetPayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 鎶曟斁鐐归€夋嫨锛氭妸宸ュ巶鍛ㄥ洿鐨勪刊瑙嗗浘鐢诲嚭鏉ワ紝鐐逛竴涓嬪喅瀹氱粨鏋勮惤鍦ㄥ摢銆? *
 * <p>涓轰粈涔堣嚜宸卞仛锛歑aero 鐨勫湴鍥炬病鏈夊彲璋冪敤鐨?API銆傝繖閲岀殑鍋氭硶鏄鎴风閲囨牱楂樺害鍥?+ 鏂瑰潡鍦板浘棰滆壊
 * 鐢熸垚涓€寮犺创鍥撅紙姣忎釜鏂瑰潡 1 鍍忕礌锛夛紝鐐归€夊悗鎶婂潗鏍囧彂缁欐湇鍔＄銆?/p>
 */
public class AssemblyMapScreen extends Screen {

    private static final int BG = 0xFF101018;
    private static final int PANEL = 0xFF23232F;
    private static final int ACCENT = 0xFF54C8FF;
    private static final int WARN = 0xFFFF7A7A;
    private static final int TEXT = 0xFFE8F4FF;
    

    private final BlockPos machinePos;
    private final int radius;

    private ResourceLocation textureId;
    private int mapLeft;
    private int mapTop;
    private int cellSize = 2;
    private int selectedX;
    private int selectedZ;
    private boolean hasSelection;
    private boolean mapReady;

    public AssemblyMapScreen(BlockPos machinePos) {
        super(Component.translatable("gui.deepspacedeployer.map_title"));
        this.machinePos = machinePos;
        this.radius = Math.max(4, Config.ASSEMBLY_MAX_RADIUS.get());
        this.selectedX = machinePos.getX();
        this.selectedZ = machinePos.getZ();
    }

    private int size() {
        return radius * 2 + 1;
    }

    @Override
    protected void init() {
        // 姣忎釜鏂瑰潡鐢诲澶э細鎸夌獥鍙ｅぇ灏忚嚜閫傚簲锛堣寖鍥村ぇ鏃剁缉鍒?1 鍍忕礌涔熻濉炲緱涓嬶級
        int maxMap = Math.max(120, Math.min(this.width - 40, this.height - 140));
        this.cellSize = Math.max(1, Math.min(4, maxMap / size()));

        int mapSize = size() * this.cellSize;
        this.mapLeft = (this.width - mapSize) / 2;
        this.mapTop = (this.height - mapSize) / 2 - 10;

        if (!this.mapReady) {
            buildMapTexture();
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.map_confirm"), b -> confirm())
                .bounds(this.width / 2 - 100, this.mapTop + mapSize + 24, 95, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.map_center"), b -> {
            this.selectedX = this.machinePos.getX();
            this.selectedZ = this.machinePos.getZ();
            this.hasSelection = true;
        }).bounds(this.width / 2 - 100, this.mapTop - 34, 95, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.deepspacedeployer.editor_close"), b -> this.onClose())
                .bounds(this.width / 2 + 5, this.mapTop + mapSize + 24, 95, 20).build());
    }

    /** 閲囨牱鍛ㄥ洿鍦板舰锛屽仛鎴愪竴寮犺创鍥撅紙姣忎釜鏂瑰潡 1 鍍忕礌锛?*/
    private void buildMapTexture() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int size = size();
        NativeImage image = new NativeImage(size, size, false);
        int centerX = this.machinePos.getX();
        int centerZ = this.machinePos.getZ();
        int baseY = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ);

        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int x = centerX - radius + dx;
                int z = centerZ - radius + dz;
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(x, Math.max(level.getMinBuildHeight(), y - 1), z);
                BlockState state = level.getBlockState(pos);
                int rgb = state.getMapColor(level, pos).col;
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                double shade = Math.max(0.55D, Math.min(1.35D, 1.0D + (y - baseY) * 0.02D));
                r = clamp((int) (r * shade));
                g = clamp((int) (g * shade));
                b = clamp((int) (b * shade));
                // NativeImage 鐢ㄧ殑鏄?ABGR
                image.setPixelRGBA(dx, dz, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        }

        DynamicTexture texture = new DynamicTexture(image);
        this.textureId = ResourceLocation.fromNamespaceAndPath(DeepSpaceDeployer.MOD_ID,
                "assembly_map_" + Integer.toHexString(System.identityHashCode(this)));
        Minecraft.getInstance().getTextureManager().register(this.textureId, texture);
        this.mapReady = true;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mapSize = size() * cellSize;
        if (mouseX >= this.mapLeft && mouseX < this.mapLeft + mapSize
                && mouseY >= this.mapTop && mouseY < this.mapTop + mapSize) {
            int cellX = (int) ((mouseX - this.mapLeft) / cellSize);
            int cellZ = (int) ((mouseY - this.mapTop) / cellSize);
            this.selectedX = this.machinePos.getX() - radius + Math.max(0, Math.min(size() - 1, cellX));
            this.selectedZ = this.machinePos.getZ() - radius + Math.max(0, Math.min(size() - 1, cellZ));
            this.hasSelection = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void confirm() {
        PacketDistributor.sendToServer(new SetAssemblyTargetPayload(
                this.machinePos.getX(), this.machinePos.getY(), this.machinePos.getZ(),
                this.selectedX, this.selectedZ));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int mapSize = size() * cellSize;
        graphics.fill(this.mapLeft - 2, this.mapTop - 2, this.mapLeft + mapSize + 2, this.mapTop + mapSize + 2, BG);
        if (this.textureId != null && this.mapReady) {
            graphics.blit(this.textureId, this.mapLeft, this.mapTop, mapSize, mapSize,
                    0.0F, 0.0F, size(), size(), size(), size());
        } else {
            graphics.fill(this.mapLeft, this.mapTop, this.mapLeft + mapSize, this.mapTop + mapSize, PANEL);
        }

        // 鏈哄櫒浣嶇疆鏍囪
        drawMarker(graphics, this.machinePos.getX(), this.machinePos.getZ(), ACCENT);
        // 閫変腑鐨勬姇鏀剧偣
        if (this.hasSelection) {
            drawMarker(graphics, this.selectedX, this.selectedZ, 0xFFFFD166);
        }

        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.mapTop - 50, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.map_hint", radius),
                this.width / 2, this.mapTop - 24, TEXT);
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.deepspacedeployer.map_selected",
                        this.selectedX, this.selectedZ, this.machinePos.getY()),
                this.width / 2, this.mapTop + mapSize + 8, 0xFFFFD166);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawMarker(GuiGraphics graphics, int blockX, int blockZ, int color) {
        int dx = blockX - (this.machinePos.getX() - radius);
        int dz = blockZ - (this.machinePos.getZ() - radius);
        if (dx < 0 || dz < 0 || dx >= size() || dz >= size()) {
            return;
        }
        int px = this.mapLeft + dx * cellSize;
        int pz = this.mapTop + dz * cellSize;
        graphics.fill(px - 1, pz - 1, px + cellSize + 1, pz - 1 + 1, color);
        graphics.fill(px - 1, pz + cellSize, px + cellSize + 1, pz + cellSize + 1, color);
        graphics.fill(px - 1, pz - 1, px, pz + cellSize + 1, color);
        graphics.fill(px + cellSize, pz - 1, px + cellSize + 1, pz + cellSize + 1, color);
    }

    @Override
    public void removed() {
        super.removed();
        if (this.textureId != null) {
            Minecraft.getInstance().getTextureManager().release(this.textureId);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
