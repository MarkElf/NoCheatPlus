/*
 * This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.neatmonster.nocheatplus.checks.inventory;

import java.util.LinkedList;
import java.util.List;

import org.bukkit.GameMode;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.block.Block;

import fr.neatmonster.nocheatplus.NCPAPIProvider;
import fr.neatmonster.nocheatplus.actions.ParameterName;
import fr.neatmonster.nocheatplus.checks.Check;
import fr.neatmonster.nocheatplus.checks.CheckType;
import fr.neatmonster.nocheatplus.checks.ViolationData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveData;
import fr.neatmonster.nocheatplus.checks.moving.model.PlayerMoveInfo;
import fr.neatmonster.nocheatplus.checks.moving.util.AuxMoving;
import fr.neatmonster.nocheatplus.checks.moving.magic.Magic;
import fr.neatmonster.nocheatplus.checks.moving.MovingData;
import fr.neatmonster.nocheatplus.utilities.location.PlayerLocation;
import fr.neatmonster.nocheatplus.utilities.StringUtil;
import fr.neatmonster.nocheatplus.utilities.location.TrigUtil;
import fr.neatmonster.nocheatplus.utilities.collision.CollisionUtil;
import fr.neatmonster.nocheatplus.compat.Bridge1_13;
import fr.neatmonster.nocheatplus.compat.Bridge1_9;
import fr.neatmonster.nocheatplus.compat.BridgeEnchant;
import fr.neatmonster.nocheatplus.players.IPlayerData;
import fr.neatmonster.nocheatplus.utilities.map.BlockProperties;
import fr.neatmonster.nocheatplus.compat.versions.ServerVersion;


/**
 * A check to prevent players from interacting with their inventory if they shouldn't be allowed to.
 */
public class InventoryMove extends Check {
    

   private final AuxMoving aux = NCPAPIProvider.getNoCheatPlusAPI().getGenericInstance(AuxMoving.class);


  /**
    * Instanties a new InventoryMove check
    *
    */
    public InventoryMove() {
        super(CheckType.INVENTORY_INVENTORYMOVE);
    }
    

  /**
    * Checks a player
    * @param player
    * @param data
    * @param pData
    * @param cc
    * @param type
    * @return true if successful
    *
    */
    public boolean check(final Player player, final InventoryData data, final IPlayerData pData, final InventoryConfig cc, final SlotType type){
        
        boolean cancel = false;
        boolean violation = false;
        List<String> tags = new LinkedList<String>();

        // TODO: Inventory click + swing packet/attack ? Would be a way to detect hitting+attack (which would catch careless players
        // who do not set their killaura to stop attacking if the inv is opened, although it should probably be done in the fight section (Fight.ImpossibleHit?))

        // Consider skipping the check altogether if the player is exposed to dolphin's grace and has boots with DepthStrider III. Players go uncomfortably fast with this combo.

       /* 
        * Important: MC allows players to swim (and keep the status) when on ground, but this is not *consistently* reflected back to the server 
        * (while still allowing them to move at swimming speed) instead, isSprinting() will return. Observed in both Spigot and PaperMC.
        *
        */
        
        // Shortcuts:
        // Movement
        final MovingData mData = pData.getGenericInstance(MovingData.class);
        final PlayerMoveData thisMove = mData.playerMoves.getCurrentMove();
        final PlayerMoveData lastMove = mData.playerMoves.getFirstPastMove();
        final PlayerMoveInfo moveInfo = aux.usePlayerMoveInfo();
        final PlayerLocation from = moveInfo.from;
        final PlayerLocation to = moveInfo.to;
        final boolean isSamePos = from.isSamePos(to); // The player is standing still, no XYZ distances.
        final boolean thisMoveOnGround = thisMove.touchedGround || thisMove.from.onGround || thisMove.to.onGround;
        final boolean fullLiquidMove = thisMove.from.inLiquid && thisMove.to.inLiquid;
        final long currentEvent = System.currentTimeMillis();
        final boolean isCollidingWithEntities = CollisionUtil.isCollidingWithEntities(player, true) && ServerVersion.compareMinecraftVersion("1.9") >= 0;
        final boolean swimming = (Bridge1_13.isSwimming(player) || mData.timeSwimming + 500 > currentEvent) || player.isSprinting(); 
        
        // Others
        final boolean creative = player.getGameMode() == GameMode.CREATIVE && ((type == SlotType.QUICKBAR) || cc.invMoveDisableCreative);
        final boolean isMerchant = (player.getOpenInventory().getTopInventory().getType() == InventoryType.MERCHANT); 
        Material blockUnder = player.getLocation().subtract(0, 0.68, 0).getBlock().getType();
        Material blockAbove = player.getLocation().add(0, 0.10, 0).getBlock().getType();
        final boolean movingOnSurface = blockUnder != null && BlockProperties.isAir(blockAbove) && BlockProperties.isLiquid(blockUnder); // Used to workaround issues with splash moves: water-water-air-air-air-water-air

        double friction = lastMove.hDistance * mData.lastFrictionHorizontal;
        double hDistDiff = Math.abs(thisMove.hDistance - lastMove.hDistance);
        double deltaFrict = Math.abs(friction - hDistDiff);
        double marginH = 0.0; double hDistMin = 0.0;
        final double[] result = setMinHDistAndFriction(player, pData, mData, cc, thisMoveOnGround, thisMove, 
                                                       lastMove, marginH, hDistMin, movingOnSurface, swimming);
        marginH = result[0];
        hDistMin = result[1];



        // Debug first.
        if (pData.isDebugActive(CheckType.INVENTORY_INVENTORYMOVE)) {
            debug(player, " hDist / minHdist= " + StringUtil.fdec3.format(thisMove.hDistance) + "/ " + StringUtil.fdec3.format(hDistMin) 
                + " \n " + " deltaFrict / margin= " + StringUtil.fdec3.format(deltaFrict) + " / " + StringUtil.fdec3.format(marginH)
                + " \n " + " hDiff / hDiffLeniency= " + StringUtil.fdec3.format(hDistDiff) + " / " + cc.invMoveHdistLeniency
                + " \n " + " yDistance= " + StringUtil.fdec3.format(thisMove.yDistance)
                + " liqTick= " + mData.liqtick);
        }
        
        // Clicking while using/consuming an item
        if (mData.isusingitem && !isMerchant){ 
            tags.add("usingitem");
            violation = true;
        }

        // ... while swimming (in water, not on ground. Players are forced to stop swimming if they open an inventory)
        else if (Bridge1_13.isSwimming(player) && !thisMoveOnGround && !isSamePos){
            violation = true;
            tags.add("isSwimming(no ground)");
        }

        // ... while swimming on the ground (players are allowed to stay into isSwimming state if on ground).
        // if delta>marginH, we assume the player to be intentionally moving and not being moved by friction.
        // Using swimming here to account for the bug described above (also to better harmonize swim-non swim transitions)
        else if (swimming && thisMove.hDistance > hDistMin && deltaFrict > marginH 
                && fullLiquidMove && thisMoveOnGround && hDistDiff < cc.invMoveHdistLeniency
                && !isSamePos){
            violation = true;
            tags.add("isSwimming(ground)");
        }

        // ... while being dead or sleeping (-> Is it even possible?)
        else if (player.isDead() || player.isSleeping()) {
            tags.add(player.isDead() ? "isDead" : "isSleeping");
            violation = true;
        }

        // ...  while sprinting
        else if (player.isSprinting() && thisMove.hDistance > thisMove.walkSpeed && !player.isFlying() 
                && mData.lostSprintCount == 0 && !fullLiquidMove){
            tags.add("isSprinting");
            violation = true;
        }

        // ... while sneaking
        else if (player.isSneaking() 
                // Skipping conditions
                && !(thisMove.downStream & mData.isdownstream) 
                && !isCollidingWithEntities
                &&  mData.sfOnIce == 0
                && !isSamePos // Allow toggle sneaking (1.13+)
                // Actual check
                && ((currentEvent - data.lastMoveEvent) < 65)
                && thisMoveOnGround 
                && hDistDiff < cc.invMoveHdistLeniency 
                && thisMove.hDistance > hDistMin
                && ((fullLiquidMove && deltaFrict > marginH) || !fullLiquidMove)
                ){
            tags.add("isSneaking");
            violation = true;
        }
        
        // Last resort, check if the player is actively moving while clicking in their inventory
        else {
            
            if (thisMove.hDistance > hDistMin
                && ((currentEvent - data.lastMoveEvent) < 65)
                && hDistDiff < cc.invMoveHdistLeniency
                // Skipping conditions
                && !(thisMove.downStream & mData.isdownstream) 
                && !mData.isVelocityJumpPhase()
                && !isCollidingWithEntities
                && !player.isInsideVehicle() 
                &&  mData.sfOnIce == 0 
                && !isSamePos 
                ){ 
                tags.add("moving");
                
                // Walking on ground in a liquid/normal ground
                if (thisMoveOnGround && (fullLiquidMove && deltaFrict > marginH || !fullLiquidMove)){
                    violation = true; 
                }
                // Moving inside liquid (but not on the ground)
                else if (fullLiquidMove && mData.liqtick > 4 && !thisMoveOnGround
                        && (deltaFrict > marginH  || thisMove.yDistance > 0.195)){
                    violation = true;
                } 
                // Moving above liquid surface
                else if (movingOnSurface && deltaFrict > marginH && !thisMoveOnGround){ 
                    violation = true;
                }
            }
        }
    

        // Handle violations 
        if (violation && !creative) {
            data.invMoveVL += 1D;
            final ViolationData vd = new ViolationData(this, player, data.invMoveVL, 1D, pData.getGenericInstance(InventoryConfig.class).invMoveActionList);
            if (vd.needsParameters()) vd.setParameter(ParameterName.TAGS, StringUtil.join(tags, "+"));
            cancel = executeActions(vd).willCancel();
        }
        // Cooldown
        else {
            data.invMoveVL *= 0.96D;
        }
    
        return cancel;
    }


  /**
    * Set the minimum horizontal distance/friction required for the checks to activate
    * @param player
    * @param pData
    * @param cc
    * @param thisMoveOnGround  from/to/or touched the ground due to a lostGround workaround being applied.
    * @param thisMove
    * @param lastMove
    * @param marginH  Minimum friction required
    * @param hDistMin  Minimum horizontal distance required
    * @param movingOnSurface
    * @param swimming
    *
    * @return marginH,hDistMin
    */
    private double[] setMinHDistAndFriction(final Player player, final IPlayerData pData, final MovingData mData, final InventoryConfig cc,
                                           final boolean thisMoveOnGround, final PlayerMoveData thisMove, final PlayerMoveData lastMove,
                                           double marginH, double hDistMin, final boolean movingOnSurface, final boolean swimming){

        // TODO: Clean this magic fumbling and make things configurable(!)
        // TODO: Custom(+Scale) leniency too?
        
        // Surface level
        if (movingOnSurface && !player.isFlying()){
            marginH = swimming ? 0.099 : 0.02;
            hDistMin = swimming ? 0.170 : 0.108;
        }
        // In liquid
        else if ((thisMove.from.inLiquid && thisMove.to.inLiquid) && mData.liqtick > 4 && !player.isFlying()) {
            marginH = swimming ? 0.099 : (player.isSneaking() ? 0.030 : 0.089); 
            hDistMin = swimming ? 0.194 : (player.isSneaking() ? 0.031 : 0.075);
            
            // Account for lava
            if ((thisMove.from.inLava || thisMove.to.inLava)){
                marginH = hDistMin = 0.038; // From testings: 0.0399...
            }
        }
        // Sneaking on the ground. 
        else if (player.isSneaking()){
            hDistMin = 0.41 * thisMove.walkSpeed; // old: 0.050
        }
        // TODO: Ice friction
        // Fallback to default min hDist.
        else {

           if (thisMove.from.onSlimeBlock) {
              hDistMin = cc.invMoveHdistMin * Magic.modSlime; 
           }
           else if (thisMove.from.inBerryBush) {
              hDistMin = cc.invMoveHdistMin * Magic.modBush;
           } 
           else if (thisMove.from.onSoulSand || thisMove.from.onHoneyBlock) {
              hDistMin = cc.invMoveHdistMin * Magic.modSoulSand;
           }
           else {
              hDistMin = cc.invMoveHdistMin;
           }
        }


        // Scale values according to the enchant, if in water.
        if (thisMove.from.inWater || thisMove.to.inWater || movingOnSurface){ // From testing (lava): in-air -> splash move (lava) with delta 0.04834
  
            final int depthStriderLevel = BridgeEnchant.getDepthStriderLevel(player);
            if (depthStriderLevel > 0) {
                marginH *= Magic.modDepthStrider[depthStriderLevel];
                hDistMin *= Magic.modDepthStrider[depthStriderLevel];
            }

            if (!Double.isInfinite(Bridge1_13.getDolphinGraceAmplifier(player))) {
                marginH *= Magic.modDolphinsGrace / Magic.WALK_SPEED; 
                hDistMin *= Magic.modDolphinsGrace / Magic.WALK_SPEED;

                if (depthStriderLevel > 1) {
                    marginH *= 1.0 + 0.007 * depthStriderLevel; 
                    hDistMin *= 1.0 + 0.007 * depthStriderLevel;
                }
            }
        }
        return new double[] {marginH, hDistMin};
    }
}
