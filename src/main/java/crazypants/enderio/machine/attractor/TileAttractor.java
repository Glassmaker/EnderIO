package crazypants.enderio.machine.attractor;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLeashKnot;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks.EntityAITaskEntry;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityEnderman;
import net.minecraft.entity.monster.EntityMagmaCube;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.monster.EntitySlime;
import net.minecraft.entity.monster.EntitySpider;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.PathEntity;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.util.FakePlayer;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.ObfuscationReflectionHelper;

import crazypants.enderio.EnderIO;
import crazypants.enderio.ModObject;
import crazypants.enderio.config.Config;
import crazypants.enderio.enderface.PacketOpenRemoteUi.PlayerProxy;
import crazypants.enderio.machine.AbstractMachineEntity;
import crazypants.enderio.machine.SlotDefinition;
import crazypants.enderio.power.BasicCapacitor;
import crazypants.enderio.power.Capacitors;
import crazypants.enderio.power.ICapacitor;
import crazypants.render.BoundingBox;
import crazypants.util.BlockCoord;
import crazypants.vecmath.Vector3d;

public class TileAttractor extends AbstractMachineEntity {

  private AxisAlignedBB attractorBounds;
  private FakePlayer target;
  private int rangeSqu;
  private int range;
  private int powerPerTick;
  private Set<EntityLiving> tracking = new HashSet<EntityLiving>();
  private int tickCounter = 0;
  private int maxMobsAttracted = 20;

  private ICapacitor capacitor;

  public TileAttractor() {
    super(new SlotDefinition(12, 0));
    setUpdrade(Capacitors.BASIC_CAPACITOR);
  }

  @Override
  public void setCapacitor(Capacitors capacitorType) {
    setUpdrade(capacitorType);
    super.setCapacitor(capacitorType);
  }

  @Override
  public ICapacitor getCapacitor() {
    return capacitor;
  }

  public int getRange() {
    return range;
  }

  private void setUpdrade(Capacitors capacitorType) {
    switch (capacitorType) {
    case ACTIVATED_CAPACITOR:
      range = Config.attractorRangeLevelTwo;
      powerPerTick = Config.attractorPowerPerTickLevelTwo;
      break;
    case ENDER_CAPACITOR:
      range = Config.attractorRangeLevelThree;
      powerPerTick = Config.attractorPowerPerTickLevelThree;
      break;
    case BASIC_CAPACITOR:
    default:
      range = Config.attractorRangeLevelOne;
      powerPerTick = Config.attractorPowerPerTickLevelOne;
      break;
    }
    rangeSqu = range * range;

    BoundingBox bb = new BoundingBox(new BlockCoord(this));
    bb = bb.scale(range, range, range);
    attractorBounds = AxisAlignedBB.getBoundingBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    capacitor = new BasicCapacitor(powerPerTick * 8, capacitorType.capacitor.getMaxEnergyStored(), powerPerTick);
  }

  @Override
  public String getMachineName() {
    return ModObject.blockAttractor.unlocalisedName;
  }

  @Override
  protected boolean isMachineItemValidForSlot(int i, ItemStack itemstack) {
    if(!slotDefinition.isInputSlot(i)) {
      return false;
    }
    String mob = EnderIO.itemSoulVessel.getMobTypeFromStack(itemstack);
    if(mob == null) {
      return false;
    }
    Class<?> cl = (Class<?>) EntityList.stringToClassMapping.get(mob);
    if(cl == null) {
      return false;
    }
    return EntityLiving.class.isAssignableFrom(cl);
  }

  @Override
  public boolean isActive() {
    return hasPower();
  }

  @Override
  public float getProgress() {
    return 0;
  }

  @Override
  protected boolean processTasks(boolean redstoneCheckPassed) {
    if(redstoneCheckPassed && hasPower()) {
      usePower();
    } else {
      return false;
    }
    tickCounter++;
    if(tickCounter < 10) {
      for (EntityLiving ent : tracking) {
        onEntityTick(ent);
      }
      return false;
    }
    tickCounter = 0;

    Set<EntityLiving> trackingThisTick = new HashSet<EntityLiving>();
    List<EntityLiving> entsInBounds = worldObj.getEntitiesWithinAABB(EntityLiving.class, attractorBounds);

    int candidates = 0;
    for (EntityLiving ent : entsInBounds) {
      if(!ent.isDead && isMobInFilter(ent)) {
        candidates++;
        if(tracking.contains(ent)) {
          trackingThisTick.add(ent);
          onEntityTick(ent);
        } else if(tracking.size() < maxMobsAttracted && trackMob(ent)) {
          trackingThisTick.add(ent);
        }
      }
    }
    tracking.clear();
    tracking = trackingThisTick;
    return false;
  }

  protected double usePower() {
    return usePower(getPowerUsePerTick());
  }

  protected int usePower(int wantToUse) {
    int used = Math.min(getEnergyStored(), wantToUse);
    setEnergyStored(Math.max(0, getEnergyStored() - used));
    return used;
  }

  @Override
  public int getPowerUsePerTick() {
    return powerPerTick;
  }

  FakePlayer getTarget() {
    if(target == null) {
      target = new Target();
    }
    return target;
  }

  public boolean canAttract(String entityId, EntityLiving mob) {
    return redstoneCheckPassed && hasPower() && isMobInFilter(entityId) && isMobInRange(mob);
  }

  private boolean isMobInRange(EntityLiving mob) {
    return isMobInRange(mob, rangeSqu);
  }

  private boolean isMobInRange(EntityLiving mob, int range) {
    if(mob == null) {
      return false;
    }
    return new Vector3d(mob.posX, mob.posY, mob.posZ).distanceSquared(new Vector3d(xCoord, yCoord, zCoord)) <= range;
  }

  private boolean isMobInFilter(EntityLiving ent) {
    return isMobInFilter(EntityList.getEntityString(ent));
  }

  private boolean isMobInFilter(String entityId) {
    for (int i = slotDefinition.minInputSlot; i <= slotDefinition.maxInputSlot; i++) {
      if(inventory[i] != null) {
        String mob = EnderIO.itemSoulVessel.getMobTypeFromStack(inventory[i]);
        if(mob != null && mob.equals(entityId)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean trackMob(EntityLiving ent) {
    if(useSetTarget(ent)) {
      ((EntityMob) ent).setTarget(getTarget());
      return true;
    } else if(useSpecialCase(ent)) {
      return applySpecialCase(ent);
    } else {
      tracking.add(ent);
      List<EntityAITaskEntry> entries = ent.tasks.taskEntries;
      boolean hasTask = false;
      EntityAIBase remove = null;
      boolean isTracked;
      for (EntityAITaskEntry entry : entries) {
        if(entry.action instanceof AttractTask) {
          AttractTask at = (AttractTask) entry.action;
          if(at.coord.equals(new BlockCoord(this)) || !at.continueExecuting()) {
            remove = entry.action;
          } else {
            return false;
          }
        }
      }
      if(remove != null) {
        ent.tasks.removeTask(remove);
      }
      ent.tasks.addTask(0, new AttractTask(ent, getTarget(), new BlockCoord(this)));
      return true;
    }
  }

  private boolean applySpecialCase(EntityLiving ent) {
    return ent instanceof EntitySlime || ent instanceof EntitySilverfish || ent instanceof EntityBlaze;
  }

  private boolean useSpecialCase(EntityLiving ent) {
    if(ent instanceof EntitySlime) {
      ent.faceEntity(getTarget(), 10.0F, 20.0F);
      return true;
    } else if(ent instanceof EntitySilverfish) {
      PathEntity pathentity = worldObj.getPathEntityToEntity(ent, getTarget(), getRange(), true, false, false, true);
      ((EntityCreature) ent).setPathToEntity(pathentity);
      return true;
    }
    else if(ent instanceof EntityBlaze) {
      return true;
    }
    return false;
  }

  private void onEntityTick(EntityLiving ent) {
    if(ent instanceof EntitySlime) {
      ent.faceEntity(getTarget(), 10.0F, 20.0F);
    } else if(ent instanceof EntitySilverfish) {
      if(tickCounter < 10) {
        return;
      }
      EntitySilverfish sf = (EntitySilverfish) ent;
      PathEntity pathentity = worldObj.getPathEntityToEntity(ent, getTarget(), getRange(), true, false, false, true);
      sf.setPathToEntity(pathentity);
    } else if(ent instanceof EntityBlaze) {
      EntityBlaze mob = (EntityBlaze) ent;

      double x = (xCoord + 0.5D - ent.posX);
      double y = (yCoord + 1D - ent.posY);
      double z = (zCoord + 0.5D - ent.posZ);
      double distance = Math.sqrt(x * x + y * y + z * z);
      if(distance > 1.25) {
        double speed = 0.01;
        ent.motionX += x / distance * speed;
        if(y > 0) {
          ent.motionY += (0.30000001192092896D - ent.motionY) * 0.30000001192092896D;
        }
        ent.motionZ += z / distance * speed;
      }
    } else if(ent instanceof EntityPigZombie || ent instanceof EntitySpider) {
     
      double x = (xCoord + 0.5D - ent.posX);
      double y = (yCoord + 1D - ent.posY);
      double z = (zCoord + 0.5D - ent.posZ);
      double distance = Math.sqrt(x * x + y * y + z * z);
      if(distance > 2) {
        EntityMob mod = (EntityMob) ent;
        mod.faceEntity(getTarget(), 180, 0);
        mod.moveEntityWithHeading(0, 1);
        if(mod.posY < yCoord) {
          mod.setJumping(true);
        } else {
          mod.setJumping(false);
        }
      }

    } 
  }

  private boolean useSetTarget(EntityLiving ent) {
    return ent instanceof EntityEnderman || ent instanceof EntityPigZombie || ent instanceof EntitySpider || ent instanceof EntitySilverfish;
  }

  private class Target extends FakePlayer {

    ItemStack prevWeapon;

    public Target() {
      super(MinecraftServer.getServer().worldServerForDimension(getWorldObj().provider.dimensionId), new GameProfile(null,
          ModObject.blockAttractor.unlocalisedName + ":" + getLocation()));
      posX = xCoord + 0.5;
      posY = yCoord + 0.5;
      posZ = zCoord + 0.5;
    }
  }

  private static class AttractTask extends EntityAIBase {

    private EntityLiving mob;
    private BlockCoord coord;
    private FakePlayer target;
    private String entityId;
    private int updatesSincePathing;

    private boolean started = false;

    private AttractTask(EntityLiving mob, FakePlayer target, BlockCoord coord) {
      this.mob = mob;
      this.coord = coord;
      this.target = target;
      entityId = EntityList.getEntityString(mob);
    }

    @Override
    public boolean shouldExecute() {
      return continueExecuting();
    }

    @Override
    public void resetTask() {
      started = false;
      updatesSincePathing = 0;
    }

    @Override
    public boolean continueExecuting() {
      boolean res = false;
      TileEntity te = mob.worldObj.getTileEntity(coord.x, coord.y, coord.z);
      if(te instanceof TileAttractor) {
        TileAttractor attractor = (TileAttractor) te;
        res = attractor.canAttract(entityId, mob);
      }
      return res;
    }

    @Override
    public boolean isInterruptible() {
      return true;
    }

    @Override
    public void updateTask() {
      if(!started || updatesSincePathing > 20) {
        started = true;
        int speed = 1;
        mob.getNavigator().setAvoidsWater(false);
        mob.getNavigator().tryMoveToEntityLiving(target, speed);
        updatesSincePathing = 0;
      } else {
        updatesSincePathing++;
      }
    }

  }

}
