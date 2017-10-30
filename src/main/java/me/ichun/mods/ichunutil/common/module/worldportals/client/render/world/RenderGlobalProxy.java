package me.ichun.mods.ichunutil.common.module.worldportals.client.render.world;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import me.ichun.mods.ichunutil.common.module.worldportals.client.render.world.chunk.IRenderChunkWorldPortal;
import me.ichun.mods.ichunutil.common.module.worldportals.client.render.world.factory.ListChunkFactory;
import me.ichun.mods.ichunutil.common.module.worldportals.client.render.world.factory.VboChunkFactory;
import me.ichun.mods.ichunutil.common.module.worldportals.common.portal.WorldPortal;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector3f;

import javax.annotation.Nullable;
import java.util.*;

public class RenderGlobalProxy extends RenderGlobal
{
    public HashMap<WorldPortal, ViewFrustum> usedViewFrustums = new HashMap<>();
    public ArrayList<ViewFrustum> freeViewFrustums = new ArrayList<>();
    public float playerPrevYaw;
    public float playerPrevPitch;
    public float playerYaw;
    public float playerPitch;
    public float playerPrevHeadYaw;
    public float playerHeadYaw;
    public double playerPosX;
    public double playerPosY;
    public double playerPosZ;
    public double playerPrevPosX;
    public double playerPrevPosY;
    public double playerPrevPosZ;
    public double playerLastTickX;
    public double playerLastTickY;
    public double playerLastTickZ;

    private List<ContainerLocalRenderInformation> renderInfos2 = Lists.<ContainerLocalRenderInformation>newArrayListWithCapacity(69696);

    public boolean released;

    public RenderGlobalProxy(Minecraft mcIn)
    {
        super(mcIn);

        if(this.vboEnabled)
        {
            this.renderContainer = new VboRenderList();
            this.renderChunkFactory = new VboChunkFactory();
        }
        else
        {
            this.renderContainer = new RenderList();
            this.renderChunkFactory = new ListChunkFactory();
        }
    }

    @Override
    public void setWorldAndLoadRenderers(@Nullable WorldClient worldClientIn)
    {
        if (this.theWorld != null)
        {
            this.theWorld.removeEventListener(this);
        }

        this.frustumUpdatePosX = Double.MIN_VALUE;
        this.frustumUpdatePosY = Double.MIN_VALUE;
        this.frustumUpdatePosZ = Double.MIN_VALUE;
        this.frustumUpdatePosChunkX = Integer.MIN_VALUE;
        this.frustumUpdatePosChunkY = Integer.MIN_VALUE;
        this.frustumUpdatePosChunkZ = Integer.MIN_VALUE;
        this.renderManager.set(worldClientIn);
        this.theWorld = worldClientIn;

        if (worldClientIn != null)
        {
            worldClientIn.addEventListener(this);
            this.loadRenderers();
        }
        else
        {
            this.chunksToUpdate.clear();
            this.renderInfos2.clear();
            if (viewFrustum != null) viewFrustum.deleteGlResources(); // Forge: Fix MC-105406
            this.viewFrustum = null;

            if (this.renderDispatcher != null)
            {
                this.renderDispatcher.stopWorkerThreads();
            }

            this.renderDispatcher = null;
        }
    }

    @Override
    public void loadRenderers()
    {
        if(this.theWorld != null)
        {
            if(this.renderDispatcher == null)
            {
                this.renderDispatcher = new ChunkRenderDispatcher();
            }

            this.displayListEntitiesDirty = true;
            Blocks.LEAVES.setGraphicsLevel(this.mc.gameSettings.fancyGraphics);
            Blocks.LEAVES2.setGraphicsLevel(this.mc.gameSettings.fancyGraphics);
            this.renderDistanceChunks = this.mc.gameSettings.renderDistanceChunks;
            boolean flag = this.vboEnabled;
            this.vboEnabled = OpenGlHelper.useVbo();

            if(flag && !this.vboEnabled)
            {
                this.renderContainer = new RenderList();
                this.renderChunkFactory = new ListChunkFactory();
            }
            else if(!flag && this.vboEnabled)
            {
                this.renderContainer = new VboRenderList();
                this.renderChunkFactory = new VboChunkFactory();
            }

            if(flag != this.vboEnabled)
            {
                this.generateStars();
                this.generateSky();
                this.generateSky2();
            }

            cleanViewFrustums();

            this.stopChunkUpdates();

            synchronized(this.setTileEntities)
            {
                this.setTileEntities.clear();
            }

            this.renderEntitiesStartupCounter = 2;
        }
    }

    public void cleanViewFrustums()
    {
        for(Map.Entry<WorldPortal, ViewFrustum> e : usedViewFrustums.entrySet())
        {
            e.getValue().deleteGlResources();
            if(e.getValue() == viewFrustum)
            {
                viewFrustum = null;
            }
        }
        usedViewFrustums.clear();
        for(ViewFrustum frustum : freeViewFrustums)
        {
            frustum.deleteGlResources();
            if(frustum == viewFrustum)
            {
                viewFrustum = null;
            }
        }
        freeViewFrustums.clear();
        if(this.viewFrustum != null)
        {
            this.viewFrustum.deleteGlResources();
        }
        freeViewFrustums.add(new ViewFrustum(this.theWorld, this.mc.gameSettings.renderDistanceChunks, this, this.renderChunkFactory));
        viewFrustum = freeViewFrustums.get(0);
    }

    public void releaseViewFrustum(WorldPortal pm)
    {
        ViewFrustum vf = usedViewFrustums.get(pm);
        if(vf != null)
        {
            freeViewFrustums.add(vf);
            usedViewFrustums.remove(pm);
        }
    }

    public void bindViewFrustum(WorldPortal pm)
    {
        ViewFrustum vf = usedViewFrustums.get(pm);
        if(vf == null)
        {
            if(freeViewFrustums.isEmpty())
            {
                vf = new ViewFrustum(this.theWorld, this.mc.gameSettings.renderDistanceChunks, this, this.renderChunkFactory);
            }
            else
            {
                vf = freeViewFrustums.get(0);
                freeViewFrustums.remove(0);
            }
            usedViewFrustums.put(pm, vf);

            if(this.theWorld != null)
            {
                Entity entity = this.mc.getRenderViewEntity();

                if(entity != null)
                {
                    vf.updateChunkPositions(entity.posX, entity.posZ);
                }
            }
        }
        viewFrustum = vf;
        for(RenderChunk renderChunk : viewFrustum.renderChunks)
        {
            if(renderChunk instanceof IRenderChunkWorldPortal)
            {
                ((IRenderChunkWorldPortal)renderChunk).setCurrentPositionAndFace(pm.getPos(), pm.getFaceOn());
            }
        }
    }

    public void storePlayerInfo()
    {
        playerPrevYaw = mc.thePlayer.prevRotationYaw;
        playerPrevPitch = mc.thePlayer.prevRotationPitch;
        playerYaw = mc.thePlayer.rotationYaw;
        playerPitch = mc.thePlayer.rotationPitch;
        playerPrevHeadYaw = mc.thePlayer.prevRotationYawHead;
        playerHeadYaw = mc.thePlayer.rotationYawHead;
        playerPosX = mc.thePlayer.posX;
        playerPosY = mc.thePlayer.posY;
        playerPosZ = mc.thePlayer.posZ;
        playerPrevPosX = mc.thePlayer.prevPosX;
        playerPrevPosY = mc.thePlayer.prevPosY;
        playerPrevPosZ = mc.thePlayer.prevPosZ;
        playerLastTickX = mc.thePlayer.lastTickPosX;
        playerLastTickY = mc.thePlayer.lastTickPosY;
        playerLastTickZ = mc.thePlayer.lastTickPosZ;
    }

    @Override
    public void renderEntities(Entity renderViewEntity, ICamera camera, float partialTicks)
    {
        renderEntities(renderViewEntity, camera, partialTicks, null, null);
    }

    public void renderEntities(Entity renderViewEntity, ICamera camera, float partialTicks, @Nullable WorldPortal portal, @Nullable WorldPortal pair) // we are rendering the pair's perspective
    {
        int pass = net.minecraftforge.client.MinecraftForgeClient.getRenderPass();
        if(this.renderEntitiesStartupCounter > 0)
        {
            if(pass > 0)
            {
                return;
            }
            --this.renderEntitiesStartupCounter;
        }
        else
        {
            double d0 = renderViewEntity.prevPosX + (renderViewEntity.posX - renderViewEntity.prevPosX) * (double)partialTicks;
            double d1 = renderViewEntity.prevPosY + (renderViewEntity.posY - renderViewEntity.prevPosY) * (double)partialTicks;
            double d2 = renderViewEntity.prevPosZ + (renderViewEntity.posZ - renderViewEntity.prevPosZ) * (double)partialTicks;
            TileEntityRendererDispatcher.instance.prepare(this.theWorld, this.mc.getTextureManager(), this.mc.fontRendererObj, this.mc.getRenderViewEntity(), this.mc.objectMouseOver, partialTicks);
            this.renderManager.cacheActiveRenderInfo(this.theWorld, this.mc.fontRendererObj, this.mc.getRenderViewEntity(), this.mc.pointedEntity, this.mc.gameSettings, partialTicks);
            if(pass == 0)
            {
                this.countEntitiesTotal = 0;
                this.countEntitiesRendered = 0;
                this.countEntitiesHidden = 0;
            }
            Entity entity = this.mc.getRenderViewEntity();
            double d3 = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * (double)partialTicks;
            double d4 = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * (double)partialTicks;
            double d5 = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * (double)partialTicks;
            TileEntityRendererDispatcher.staticPlayerX = d3;
            TileEntityRendererDispatcher.staticPlayerY = d4;
            TileEntityRendererDispatcher.staticPlayerZ = d5;
            this.renderManager.setRenderPosition(d3, d4, d5);
            this.mc.entityRenderer.enableLightmap();
            List<Entity> list = this.theWorld.getLoadedEntityList();
            if(pass == 0)
            {
                this.countEntitiesTotal = list.size();
            }

            for(int i = 0; i < this.theWorld.weatherEffects.size(); ++i)
            {
                Entity entity1 = this.theWorld.weatherEffects.get(i);
                if(pair != null && !shouldRenderEntity(entity1, pair) || !entity1.shouldRenderInPass(pass))
                {
                    continue;
                }
                ++this.countEntitiesRendered;

                if(entity1.isInRangeToRender3d(d0, d1, d2))
                {
                    this.renderManager.renderEntityStatic(entity1, partialTicks, false);
                }
            }

            List<Entity> list1 = Lists.newArrayList();
            List<Entity> list2 = Lists.newArrayList();

            float prevYaw = mc.thePlayer.prevRotationYaw;
            float prevPitch = mc.thePlayer.prevRotationPitch;
            float yaw = mc.thePlayer.rotationYaw;
            float pitch = mc.thePlayer.rotationPitch;
            float prevYawHead = mc.thePlayer.prevRotationYawHead;
            float yawHead = mc.thePlayer.rotationYawHead;
            double posX = mc.thePlayer.posX;
            double posY = mc.thePlayer.posY;
            double posZ = mc.thePlayer.posZ;
            double prevPosX = mc.thePlayer.prevPosX;
            double prevPosY = mc.thePlayer.prevPosY;
            double prevPosZ = mc.thePlayer.prevPosZ;
            double lastX = mc.thePlayer.lastTickPosX;
            double lastY = mc.thePlayer.lastTickPosY;
            double lastZ = mc.thePlayer.lastTickPosZ;

            mc.thePlayer.prevRotationYaw = playerPrevYaw;
            mc.thePlayer.prevRotationPitch = playerPrevPitch;
            mc.thePlayer.rotationYaw = playerYaw;
            mc.thePlayer.rotationPitch = playerPitch;
            mc.thePlayer.prevRotationYawHead = playerPrevHeadYaw;
            mc.thePlayer.rotationYawHead = playerHeadYaw;
            mc.thePlayer.posX = playerPosX;
            mc.thePlayer.posY = playerPosY;
            mc.thePlayer.posZ = playerPosZ;
            mc.thePlayer.prevPosX = playerPrevPosX;
            mc.thePlayer.prevPosY = playerPrevPosY;
            mc.thePlayer.prevPosZ = playerPrevPosZ;
            mc.thePlayer.lastTickPosX = playerLastTickX;
            mc.thePlayer.lastTickPosY = playerLastTickY;
            mc.thePlayer.lastTickPosZ = playerLastTickZ;

            for(ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation : this.renderInfos2)
            {
                Chunk chunk = this.theWorld.getChunkFromBlockCoords(renderglobal$containerlocalrenderinformation.renderChunk.getPosition());
                ClassInheritanceMultiMap<Entity> classinheritancemultimap = chunk.getEntityLists()[renderglobal$containerlocalrenderinformation.renderChunk.getPosition().getY() / 16];

                if(!classinheritancemultimap.isEmpty())
                {
                    for(Entity entity2 : classinheritancemultimap)
                    {
                        if(portal != null && pair != null && !(entity2 == mc.thePlayer && mc.gameSettings.thirdPersonView == 0) && portal.lastScanEntities.contains(entity2) && portal.getPortalInsides(entity2).intersectsWith(entity2.getEntityBoundingBox()))
                        {
                            double eePosX = entity2.lastTickPosX + (entity2.posX - entity2.lastTickPosX) * (double)partialTicks;
                            double eePosY = entity2.lastTickPosY + (entity2.posY - entity2.lastTickPosY) * (double)partialTicks;
                            double eePosZ = entity2.lastTickPosZ + (entity2.posZ - entity2.lastTickPosZ) * (double)partialTicks;

                            AxisAlignedBB flatPlane = portal.getFlatPlane();
                            double centerX = (flatPlane.maxX + flatPlane.minX) / 2D;
                            double centerY = (flatPlane.maxY + flatPlane.minY) / 2D;
                            double centerZ = (flatPlane.maxZ + flatPlane.minZ) / 2D;

                            AxisAlignedBB pairFlatPlane = portal.getPair().getFlatPlane();
                            double destX = (pairFlatPlane.maxX + pairFlatPlane.minX) / 2D;
                            double destY = (pairFlatPlane.maxY + pairFlatPlane.minY) / 2D;
                            double destZ = (pairFlatPlane.maxZ + pairFlatPlane.minZ) / 2D;
                            GlStateManager.pushMatrix();

                            double rotX = eePosX - d3;
                            double rotY = eePosY - d4;
                            double rotZ = eePosZ - d5;

                            float[] appliedOffset = portal.getQuaternionFormula().applyPositionalRotation(new float[] { (float)(eePosX - centerX), (float)(eePosY - centerY), (float)(eePosZ - centerZ) });
                            float[] appliedRot = portal.getQuaternionFormula().applyRotationalRotation(new float[] {
                                    180F,
                                    0F,
                                    0F
                            });

                            GlStateManager.translate(destX - eePosX + appliedOffset[0], destY - eePosY + appliedOffset[1], destZ - eePosZ + appliedOffset[2]);
                            GlStateManager.translate(rotX, rotY, rotZ);

                            GlStateManager.rotate(-appliedRot[0], 0F, 1F, 0F);
                            GlStateManager.rotate(-appliedRot[1], 1F, 0F, 0F);
                            GlStateManager.rotate(-appliedRot[2], 0F, 0F, 1F);

                            GlStateManager.translate(-(rotX), -(rotY), -(rotZ));

                            this.renderManager.renderEntityStatic(entity2, partialTicks, false);

                            GlStateManager.popMatrix();
                        }

                        if(pair != null && !shouldRenderEntity(entity2, pair) || !entity2.shouldRenderInPass(pass))
                        {
                            continue;
                        }
                        boolean flag = this.renderManager.shouldRender(entity2, camera, d0, d1, d2) || entity2 == mc.thePlayer || entity2.isRidingOrBeingRiddenBy(this.mc.thePlayer);

                        if(flag && (entity2.posY < 0.0D || entity2.posY >= 256.0D || this.theWorld.isBlockLoaded(new BlockPos(entity2))))
                        {
                            ++this.countEntitiesRendered;
                            boolean disableStencil = false;
                            if(pair != null && pair.lastScanEntities.contains(entity2) && pair.portalInsides.intersectsWith(entity2.getEntityBoundingBox()))
                            {
                                disableStencil = true;
                            }
                            if(disableStencil)
                            {
                                GL11.glDisable(GL11.GL_STENCIL_TEST);
                            }
                            this.renderManager.renderEntityStatic(entity2, partialTicks, false);
                            if(disableStencil)
                            {
                                GL11.glEnable(GL11.GL_STENCIL_TEST);
                            }

                            if(this.isOutlineActive(entity2, entity, camera))
                            {
                                list1.add(entity2);
                            }

                            if(this.renderManager.isRenderMultipass(entity2))
                            {
                                list2.add(entity2);
                            }
                        }
                    }
                }
            }

            if(!list2.isEmpty())
            {
                for(Entity entity3 : list2)
                {
                    this.renderManager.renderMultipass(entity3, partialTicks);
                }
            }

            if(this.isRenderEntityOutlines() && (!list1.isEmpty() || this.entityOutlinesRendered))
            {
                this.entityOutlineFramebuffer.framebufferClear();
                this.entityOutlinesRendered = !list1.isEmpty();

                if(!list1.isEmpty())
                {
                    GlStateManager.depthFunc(519);
                    GlStateManager.disableFog();
                    this.entityOutlineFramebuffer.bindFramebuffer(false);
                    RenderHelper.disableStandardItemLighting();
                    this.renderManager.setRenderOutlines(true);

                    for(int j = 0; j < ((List)list1).size(); ++j)
                    {
                        this.renderManager.renderEntityStatic(list1.get(j), partialTicks, false);
                    }

                    this.renderManager.setRenderOutlines(false);
                    RenderHelper.enableStandardItemLighting();
                    GlStateManager.depthMask(false);
                    this.entityOutlineShader.loadShaderGroup(partialTicks);
                    GlStateManager.enableLighting();
                    GlStateManager.depthMask(true);
                    GlStateManager.enableFog();
                    GlStateManager.enableBlend();
                    GlStateManager.enableColorMaterial();
                    GlStateManager.depthFunc(515);
                    GlStateManager.enableDepth();
                    GlStateManager.enableAlpha();
                }

                this.mc.getFramebuffer().bindFramebuffer(false);
            }

            mc.thePlayer.prevRotationYaw = prevYaw;
            mc.thePlayer.prevRotationPitch = prevPitch;
            mc.thePlayer.rotationYaw = yaw;
            mc.thePlayer.rotationPitch = pitch;
            mc.thePlayer.prevRotationYawHead = prevYawHead;
            mc.thePlayer.rotationYawHead = yawHead;
            mc.thePlayer.posX = posX;
            mc.thePlayer.posY = posY;
            mc.thePlayer.posZ = posZ;
            mc.thePlayer.prevPosX = prevPosX;
            mc.thePlayer.prevPosY = prevPosY;
            mc.thePlayer.prevPosZ = prevPosZ;
            mc.thePlayer.lastTickPosX = lastX;
            mc.thePlayer.lastTickPosY = lastY;
            mc.thePlayer.lastTickPosZ = lastZ;

            RenderHelper.enableStandardItemLighting();

            TileEntityRendererDispatcher.instance.preDrawBatch();
            for(ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation1 : this.renderInfos2)
            {
                List<TileEntity> list3 = renderglobal$containerlocalrenderinformation1.renderChunk.getCompiledChunk().getTileEntities();

                if(!list3.isEmpty())
                {
                    for(TileEntity tileentity2 : list3)
                    {
                        if(!tileentity2.shouldRenderInPass(pass) || !camera.isBoundingBoxInFrustum(tileentity2.getRenderBoundingBox()))
                        {
                            continue;
                        }
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileentity2, partialTicks, -1);
                    }
                }
            }

            synchronized(this.setTileEntities)
            {
                for(TileEntity tileentity : this.setTileEntities)
                {
                    if(!tileentity.shouldRenderInPass(pass) || !camera.isBoundingBoxInFrustum(tileentity.getRenderBoundingBox()))
                    {
                        continue;
                    }
                    TileEntityRendererDispatcher.instance.renderTileEntity(tileentity, partialTicks, -1);
                }
            }
            TileEntityRendererDispatcher.instance.drawBatch(pass);

            this.preRenderDamagedBlocks();

            for(DestroyBlockProgress destroyblockprogress : this.damagedBlocks.values())
            {
                BlockPos blockpos = destroyblockprogress.getPosition();
                TileEntity tileentity1 = this.theWorld.getTileEntity(blockpos);

                if(tileentity1 instanceof TileEntityChest)
                {
                    TileEntityChest tileentitychest = (TileEntityChest)tileentity1;

                    if(tileentitychest.adjacentChestXNeg != null)
                    {
                        blockpos = blockpos.offset(EnumFacing.WEST);
                        tileentity1 = this.theWorld.getTileEntity(blockpos);
                    }
                    else if(tileentitychest.adjacentChestZNeg != null)
                    {
                        blockpos = blockpos.offset(EnumFacing.NORTH);
                        tileentity1 = this.theWorld.getTileEntity(blockpos);
                    }
                }

                Block block = this.theWorld.getBlockState(blockpos).getBlock();

                if(tileentity1 != null && tileentity1.shouldRenderInPass(pass) && tileentity1.canRenderBreaking() && camera.isBoundingBoxInFrustum(tileentity1.getRenderBoundingBox()))
                {
                    TileEntityRendererDispatcher.instance.renderTileEntity(tileentity1, partialTicks, destroyblockprogress.getPartialBlockDamage());
                }
            }

            this.postRenderDamagedBlocks();
            this.mc.entityRenderer.disableLightmap();
        }
    }

    public void setupTerrain(Entity viewEntity, double partialTicks, ICamera camera, int frameCount, boolean playerSpectator)
    {
        if (this.mc.gameSettings.renderDistanceChunks != this.renderDistanceChunks)
        {
            this.loadRenderers();
        }

        this.theWorld.theProfiler.startSection("camera");
        double d0 = viewEntity.posX - this.frustumUpdatePosX;
        double d1 = viewEntity.posY - this.frustumUpdatePosY;
        double d2 = viewEntity.posZ - this.frustumUpdatePosZ;

        if (this.frustumUpdatePosChunkX != viewEntity.chunkCoordX || this.frustumUpdatePosChunkY != viewEntity.chunkCoordY || this.frustumUpdatePosChunkZ != viewEntity.chunkCoordZ || d0 * d0 + d1 * d1 + d2 * d2 > 16.0D)
        {
            this.frustumUpdatePosX = viewEntity.posX;
            this.frustumUpdatePosY = viewEntity.posY;
            this.frustumUpdatePosZ = viewEntity.posZ;
            this.frustumUpdatePosChunkX = viewEntity.chunkCoordX;
            this.frustumUpdatePosChunkY = viewEntity.chunkCoordY;
            this.frustumUpdatePosChunkZ = viewEntity.chunkCoordZ;
            this.viewFrustum.updateChunkPositions(viewEntity.posX, viewEntity.posZ);
        }

        this.theWorld.theProfiler.endStartSection("renderlistcamera");
        double d3 = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * partialTicks;
        double d4 = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * partialTicks;
        double d5 = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * partialTicks;
        this.renderContainer.initialize(d3, d4, d5);
        this.theWorld.theProfiler.endStartSection("cull");

        if (this.debugFixedClippingHelper != null)
        {
            Frustum frustum = new Frustum(this.debugFixedClippingHelper);
            frustum.setPosition(this.debugTerrainFrustumPosition.x, this.debugTerrainFrustumPosition.y, this.debugTerrainFrustumPosition.z);
            camera = frustum;
        }

        this.mc.mcProfiler.endStartSection("culling");
        BlockPos blockpos1 = new BlockPos(d3, d4 + (double)viewEntity.getEyeHeight(), d5);
        RenderChunk renderchunk = this.viewFrustum.getRenderChunk(blockpos1);
        BlockPos blockpos = new BlockPos(MathHelper.floor_double(d3 / 16.0D) * 16, MathHelper.floor_double(d4 / 16.0D) * 16, MathHelper.floor_double(d5 / 16.0D) * 16);
        this.displayListEntitiesDirty = this.displayListEntitiesDirty || !this.chunksToUpdate.isEmpty() || viewEntity.posX != this.lastViewEntityX || viewEntity.posY != this.lastViewEntityY || viewEntity.posZ != this.lastViewEntityZ || (double)viewEntity.rotationPitch != this.lastViewEntityPitch || (double)viewEntity.rotationYaw != this.lastViewEntityYaw;
        this.lastViewEntityX = viewEntity.posX;
        this.lastViewEntityY = viewEntity.posY;
        this.lastViewEntityZ = viewEntity.posZ;
        this.lastViewEntityPitch = (double)viewEntity.rotationPitch;
        this.lastViewEntityYaw = (double)viewEntity.rotationYaw;
        boolean flag = this.debugFixedClippingHelper != null;
        this.mc.mcProfiler.endStartSection("update");

        if (!flag && this.displayListEntitiesDirty)
        {
            this.displayListEntitiesDirty = false;
            this.renderInfos2 = Lists.<ContainerLocalRenderInformation>newArrayList();
            Queue<ContainerLocalRenderInformation> queue = Queues.<ContainerLocalRenderInformation>newArrayDeque();
            Entity.setRenderDistanceWeight(MathHelper.clamp_double((double)this.mc.gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D));
            boolean flag1 = this.mc.renderChunksMany;

            if (renderchunk != null)
            {
                boolean flag2 = false;
                ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation3 = new ContainerLocalRenderInformation(renderchunk, (EnumFacing)null, 0);
                Set<EnumFacing> set1 = this.getVisibleFacings(blockpos1);

                if (set1.size() == 1)
                {
                    Vector3f vector3f = this.getViewVector(viewEntity, partialTicks);
                    EnumFacing enumfacing = EnumFacing.getFacingFromVector(vector3f.x, vector3f.y, vector3f.z).getOpposite();
                    set1.remove(enumfacing);
                }

                if (set1.isEmpty())
                {
                    flag2 = true;
                }

                if (flag2 && !playerSpectator)
                {
                    this.renderInfos2.add(renderglobal$containerlocalrenderinformation3);
                }
                else
                {
                    if (playerSpectator && this.theWorld.getBlockState(blockpos1).isOpaqueCube())
                    {
                        flag1 = false;
                    }

                    renderchunk.setFrameIndex(frameCount);
                    queue.add(renderglobal$containerlocalrenderinformation3);
                }
            }
            else
            {
                int i = blockpos1.getY() > 0 ? 248 : 8;

                for (int j = -this.renderDistanceChunks; j <= this.renderDistanceChunks; ++j)
                {
                    for (int k = -this.renderDistanceChunks; k <= this.renderDistanceChunks; ++k)
                    {
                        RenderChunk renderchunk1 = this.viewFrustum.getRenderChunk(new BlockPos((j << 4) + 8, i, (k << 4) + 8));

                        if (renderchunk1 != null && ((ICamera)camera).isBoundingBoxInFrustum(renderchunk1.boundingBox))
                        {
                            renderchunk1.setFrameIndex(frameCount);
                            queue.add(new ContainerLocalRenderInformation(renderchunk1, (EnumFacing)null, 0));
                        }
                    }
                }
            }

            this.mc.mcProfiler.startSection("iteration");

            while (!((Queue)queue).isEmpty())
            {
                ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation1 = (ContainerLocalRenderInformation)queue.poll();
                RenderChunk renderchunk3 = renderglobal$containerlocalrenderinformation1.renderChunk;
                EnumFacing enumfacing2 = renderglobal$containerlocalrenderinformation1.facing;
                this.renderInfos2.add(renderglobal$containerlocalrenderinformation1);

                for (EnumFacing enumfacing1 : EnumFacing.values())
                {
                    RenderChunk renderchunk2 = this.getRenderChunkOffset(blockpos, renderchunk3, enumfacing1);

                    if ((!flag1 || !renderglobal$containerlocalrenderinformation1.hasDirection(enumfacing1.getOpposite())) && (!flag1 || enumfacing2 == null || renderchunk3.getCompiledChunk().isVisible(enumfacing2.getOpposite(), enumfacing1)) && renderchunk2 != null && renderchunk2.setFrameIndex(frameCount) && ((ICamera)camera).isBoundingBoxInFrustum(renderchunk2.boundingBox))
                    {
                        ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation = new ContainerLocalRenderInformation(renderchunk2, enumfacing1, renderglobal$containerlocalrenderinformation1.counter + 1);
                        renderglobal$containerlocalrenderinformation.setDirection(renderglobal$containerlocalrenderinformation1.setFacing, enumfacing1);
                        queue.add(renderglobal$containerlocalrenderinformation);
                    }
                }
            }

            this.mc.mcProfiler.endSection();
        }

        this.mc.mcProfiler.endStartSection("captureFrustum");

        if (this.debugFixTerrainFrustum)
        {
            this.fixTerrainFrustum(d3, d4, d5);
            this.debugFixTerrainFrustum = false;
        }

        this.mc.mcProfiler.endStartSection("rebuildNear");
        Set<RenderChunk> set = this.chunksToUpdate;
        this.chunksToUpdate = Sets.<RenderChunk>newLinkedHashSet();

        for (ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation2 : this.renderInfos2)
        {
            RenderChunk renderchunk4 = renderglobal$containerlocalrenderinformation2.renderChunk;

            if (renderchunk4.isNeedsUpdate() || set.contains(renderchunk4))
            {
                this.displayListEntitiesDirty = true;
                BlockPos blockpos2 = renderchunk4.getPosition().add(8, 8, 8);
                boolean flag3 = blockpos2.distanceSq(blockpos1) < 768.0D;

                if (!renderchunk4.isNeedsUpdateCustom() && !flag3)
                {
                    this.chunksToUpdate.add(renderchunk4);
                }
                else
                {
                    this.mc.mcProfiler.startSection("build near");
                    this.renderDispatcher.updateChunkNow(renderchunk4);
                    renderchunk4.clearNeedsUpdate();
                    this.mc.mcProfiler.endSection();
                }
            }
        }

        this.chunksToUpdate.addAll(set);
        this.mc.mcProfiler.endSection();
    }

    public int renderBlockLayer(BlockRenderLayer blockLayerIn, double partialTicks, int pass, Entity entityIn)
    {
        RenderHelper.disableStandardItemLighting();

        if (blockLayerIn == BlockRenderLayer.TRANSLUCENT)
        {
            this.mc.mcProfiler.startSection("translucent_sort");
            double d0 = entityIn.posX - this.prevRenderSortX;
            double d1 = entityIn.posY - this.prevRenderSortY;
            double d2 = entityIn.posZ - this.prevRenderSortZ;

            if (d0 * d0 + d1 * d1 + d2 * d2 > 1.0D)
            {
                this.prevRenderSortX = entityIn.posX;
                this.prevRenderSortY = entityIn.posY;
                this.prevRenderSortZ = entityIn.posZ;
                int k = 0;

                for (ContainerLocalRenderInformation renderglobal$containerlocalrenderinformation : this.renderInfos2)
                {
                    if (renderglobal$containerlocalrenderinformation.renderChunk.compiledChunk.isLayerStarted(blockLayerIn) && k++ < 15)
                    {
                        this.renderDispatcher.updateTransparencyLater(renderglobal$containerlocalrenderinformation.renderChunk);
                    }
                }
            }

            this.mc.mcProfiler.endSection();
        }

        this.mc.mcProfiler.startSection("filterempty");
        int l = 0;
        boolean flag = blockLayerIn == BlockRenderLayer.TRANSLUCENT;
        int i1 = flag ? this.renderInfos2.size() - 1 : 0;
        int i = flag ? -1 : this.renderInfos2.size();
        int j1 = flag ? -1 : 1;

        for (int j = i1; j != i; j += j1)
        {
            RenderChunk renderchunk = ((ContainerLocalRenderInformation)this.renderInfos2.get(j)).renderChunk;

            if (!renderchunk.getCompiledChunk().isLayerEmpty(blockLayerIn))
            {
                ++l;
                this.renderContainer.addRenderChunk(renderchunk, blockLayerIn);
            }
        }

        this.mc.mcProfiler.endStartSection("render_" + blockLayerIn);
        this.renderBlockLayer(blockLayerIn);
        this.mc.mcProfiler.endSection();
        return l;
    }

    public boolean shouldRenderEntity(Entity ent, WorldPortal portal)
    {
        return !(portal.getFaceOn().getFrontOffsetX() < 0 && ent.posX > portal.getFlatPlane().minX || portal.getFaceOn().getFrontOffsetX() > 0 && ent.posX < portal.getFlatPlane().minX ||
                portal.getFaceOn().getFrontOffsetY() < 0 && (ent.getEntityBoundingBox().maxY + ent.getEntityBoundingBox().minY) / 2D > portal.getFlatPlane().minY ||
                portal.getFaceOn().getFrontOffsetY() > 0 && (ent.getEntityBoundingBox().maxY + ent.getEntityBoundingBox().minY) / 2D < portal.getFlatPlane().minY ||
                portal.getFaceOn().getFrontOffsetZ() < 0 && ent.posZ > portal.getFlatPlane().minZ || portal.getFaceOn().getFrontOffsetZ() > 0 && ent.posZ < portal.getFlatPlane().minZ);
    }

    @Override
    public void markBlocksForUpdate(int p_184385_1_, int p_184385_2_, int p_184385_3_, int p_184385_4_, int p_184385_5_, int p_184385_6_, boolean p_184385_7_)
    {
        for(Map.Entry<WorldPortal, ViewFrustum> e : usedViewFrustums.entrySet())
        {
            e.getValue().markBlocksForUpdate(p_184385_1_, p_184385_2_, p_184385_3_, p_184385_4_, p_184385_5_, p_184385_6_, p_184385_7_);
        }
        for(ViewFrustum frustum : freeViewFrustums)
        {
            frustum.markBlocksForUpdate(p_184385_1_, p_184385_2_, p_184385_3_, p_184385_4_, p_184385_5_, p_184385_6_, p_184385_7_);
        }
    }

    @Override
    public void playRecord(@Nullable SoundEvent soundIn, BlockPos pos) {}

    @Override
    public void playSoundToAllNearExcept(@Nullable EntityPlayer player, SoundEvent soundIn, SoundCategory category, double x, double y, double z, float volume, float pitch) {}

    @Override
    public void broadcastSound(int soundID, BlockPos pos, int data) {}

    public void playEvent(EntityPlayer player, int type, BlockPos blockPosIn, int data) {}

    @SideOnly(Side.CLIENT)
    public class ContainerLocalRenderInformation
    {
        public final RenderChunk renderChunk;
        public final EnumFacing facing;
        public byte setFacing;
        public final int counter;

        private ContainerLocalRenderInformation(RenderChunk renderChunkIn, EnumFacing facingIn, @Nullable int counterIn)
        {
            this.renderChunk = renderChunkIn;
            this.facing = facingIn;
            this.counter = counterIn;
        }

        public void setDirection(byte p_189561_1_, EnumFacing p_189561_2_)
        {
            this.setFacing = (byte)(this.setFacing | p_189561_1_ | 1 << p_189561_2_.ordinal());
        }

        public boolean hasDirection(EnumFacing p_189560_1_)
        {
            return (this.setFacing & 1 << p_189560_1_.ordinal()) > 0;
        }
    }
}