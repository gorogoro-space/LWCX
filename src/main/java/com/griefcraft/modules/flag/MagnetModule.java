/*
 * Copyright 2011 Tyler Blair. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ''AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE AUTHOR OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and contributors and should not be interpreted as representing official policies,
 * either expressed or implied, of anybody else.
 */

package com.griefcraft.modules.flag;

import com.griefcraft.lwc.LWC;
import com.griefcraft.model.Flag;
import com.griefcraft.model.Protection;
import com.griefcraft.scripting.JavaModule;
import com.griefcraft.scripting.event.LWCMagnetPullEvent;
import com.griefcraft.util.EnumUtil;
import com.griefcraft.util.config.Configuration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class MagnetModule extends JavaModule {
    @SuppressWarnings("ExcessiveLambdaUsage")
    private static final EntityType ITEM_ENTITY_TYPE = EnumUtil
            .valueOf(EntityType.class, "DROPPED_ITEM") // 1.20.4 and prior
            .orElseGet(() -> EntityType.ITEM); // 1.20.5 and above

    private Configuration configuration = Configuration.load("magnet.yml");

    /**
     * If this module is enabled
     */
    private boolean enabled = false;

    /**
     * The item blacklist
     */
    private List<Material> itemBlacklist;

    /**
     * The radius around the container in which to suck up items
     */
    private int radius;

    /**
     * How many items to check each time
     */
    private int perSweep;

    /**
     * Maximum pickup delay of items to suck up
     */
    private int maxPickupDelay;

    /**
     * The current entity queue
     */
    private final Queue<MagnetNode> items = new LinkedList<>();

    /**
     * The ids of the items that are in the queue
     */
    private final Set<UUID> queuedItems = new HashSet<>();

    /**
     * The number of ticks in one sweep. Every magnet protection is searched once
     * and up to perSweep items are deposited per sweep.
     */
    private static final int SWEEP_TICKS = 50;

    private class MagnetNode {
        Item item;
        Protection protection;

        /**
         * True if queued from the cached magnet protections
         */
        boolean fromCache;
    }

    private enum DepositResult {
        DEPOSITED, SKIPPED, FAILED
    }

    // does all of the work
    // searches the worlds for items and magnet chests nearby
    private class MagnetTask implements Runnable {
        /**
         * The tick within the current sweep (0 to SWEEP_TICKS - 1)
         */
        private int tick = 0;

        /**
         * True if the current sweep searches around the cached magnet protections
         */
        private boolean sweepFromCache = false;

        /**
         * The magnet protections to search in the current sweep, and the next one to search
         */
        private List<Protection> sweepMagnets = new ArrayList<>();
        private int sweepIndex = 0;

        /**
         * The number of magnet protections to search per tick in the current sweep
         */
        private int magnetsPerTick = 0;

        /**
         * The result of canPull for the items already checked in the current sweep
         */
        private Map<UUID, Boolean> sweepChecked = new HashMap<>();

        /**
         * Deposit credit, in 1/SWEEP_TICKS of an item. perSweep is added every tick
         * and one item costs SWEEP_TICKS, so perSweep items are deposited per sweep.
         * It is capped so unused credit can't build up into a burst of deposits.
         */
        private int depositCredit = 0;

        public void run() {
            LWC lwc = LWC.getInstance();

            if (tick == 0) {
                startSweep(lwc);
            }

            try {
                if (sweepFromCache) {
                    // spread the search and the deposits evenly over the ticks of the sweep
                    int end = Math.min(sweepIndex + magnetsPerTick, sweepMagnets.size());
                    for (; sweepIndex < end; sweepIndex++) {
                        queueFromMagnet(lwc, sweepMagnets.get(sweepIndex));
                    }

                    int rate = Math.max(perSweep, 1);
                    int maxPerTick = (rate + SWEEP_TICKS - 1) / SWEEP_TICKS;
                    depositCredit = Math.min(depositCredit + rate, maxPerTick * SWEEP_TICKS);

                    int deposited = depositSpread(lwc, depositCredit / SWEEP_TICKS);
                    depositCredit -= deposited * SWEEP_TICKS;
                } else if (tick == 0) {
                    // Do we need to requeue?
                    if (items.isEmpty()) {
                        queueFromItems(Bukkit.getServer(), lwc);
                    }

                    depositAll(lwc);
                }
            } finally {
                tick = (tick + 1) % SWEEP_TICKS;
            }
        }

        /**
         * Start a new sweep
         */
        private void startSweep(LWC lwc) {
            // When every protection is cached we know every magnet protection, so
            // search around those instead of around every item in every world
            sweepFromCache = lwc.getProtectionCache().isComplete();
            sweepMagnets = sweepFromCache ? lwc.getProtectionCache().getMagnetProtections() : new ArrayList<>();
            sweepIndex = 0;
            magnetsPerTick = (sweepMagnets.size() + SWEEP_TICKS - 1) / SWEEP_TICKS;
            sweepChecked = new HashMap<>();
        }

        /**
         * Deposit queued items until the given number of items were deposited
         *
         * @return the number of items deposited
         */
        private int depositSpread(LWC lwc, int budget) {
            int deposited = 0;
            MagnetNode node;

            while (deposited < budget && (node = pollNode()) != null) {
                // the magnet may have been removed since the item was queued
                if (node.fromCache && !lwc.getProtectionCache().isMagnet(node.protection)) {
                    continue;
                }

                DepositResult result = deposit(lwc, node);

                if (result == DepositResult.FAILED) {
                    break;
                }

                if (result == DepositResult.DEPOSITED) {
                    deposited++;
                }
            }

            return deposited;
        }

        /**
         * Deposit queued items in one go, as before (used when not every protection is cached)
         */
        private void depositAll(LWC lwc) {
            // Throttle amount of items polled
            int count = 0;
            MagnetNode node;

            while ((node = pollNode()) != null) {
                DepositResult result = deposit(lwc, node);

                if (result == DepositResult.FAILED) {
                    return;
                }

                if (result == DepositResult.SKIPPED) {
                    continue;
                }

                if (count > perSweep) {
                    break;
                }

                count++;
            }
        }

        private MagnetNode pollNode() {
            MagnetNode node = items.poll();

            if (node != null) {
                queuedItems.remove(node.item.getUniqueId());
            }

            return node;
        }

        private void offerNode(Item item, Protection protection, boolean fromCache) {
            MagnetNode node = new MagnetNode();
            node.item = item;
            node.protection = protection;
            node.fromCache = fromCache;
            items.offer(node);
            queuedItems.add(item.getUniqueId());
        }

        /**
         * Deposit one queued item into its magnet protection
         */
        private DepositResult deposit(LWC lwc, MagnetNode node) {
            Item item = node.item;
            Protection protection = node.protection;

            World world = item.getWorld();
            ItemStack itemStack = item.getItemStack();
            Location location = item.getLocation();
            Block block = protection.getBlock();

            if (item.isDead()) {
                return DepositResult.SKIPPED;
            }

            // Remove the items and suck them up :3
            Map<Integer, ItemStack> remaining;
            try {
                remaining = lwc.depositItems(block, itemStack);
            } catch (Exception e) {
                e.printStackTrace();
                return DepositResult.FAILED;
            }

            // we cancelled the item drop for some reason
            if (remaining == null) {
                return DepositResult.SKIPPED;
            }

            if (remaining.size() == 1) {
                ItemStack other = remaining.values().iterator().next();

                if (itemStack.getType() == other.getType() && itemStack.getAmount() == other.getAmount()) {
                    return DepositResult.SKIPPED;
                }
            }
            // remove the item on the ground
            item.remove();

            // if we have a remainder, we need to drop them
            if (remaining.size() > 0) {
                for (ItemStack stack : remaining.values()) {
                    world.dropItemNaturally(location, stack);
                }
            }

            return DepositResult.DEPOSITED;
        }

        /**
         * Queue the items that are within the radius of a cached magnet protection
         */
        private void queueFromMagnet(LWC lwc, Protection protection) {
            // the magnet may have been removed since the sweep started
            if (!lwc.getProtectionCache().isMagnet(protection)) {
                return;
            }

            World world = protection.getBukkitWorld();
            if (world == null) {
                return;
            }

            int baseX = protection.getX();
            int baseY = protection.getY();
            int baseZ = protection.getZ();

            // never load chunks just to look for items
            if (!world.isChunkLoaded(baseX >> 4, baseZ >> 4)) {
                return;
            }

            Block block = world.getBlockAt(baseX, baseY, baseZ);

            // we only want inventory blocks
            if (!(block.getState(false) instanceof InventoryHolder)) {
                return;
            }

            boolean shulkerBox = block.getType().toString().contains("SHULKER_BOX");

            // the same blocks that loadProtections(world, x, y, z, radius) covers
            BoundingBox box = new BoundingBox(baseX - radius, baseY - radius, baseZ - radius,
                    baseX + radius + 1, baseY + radius + 1, baseZ + radius + 1);

            for (Entity entity : world.getNearbyEntities(box, entity -> entity instanceof Item)) {
                Item item = (Item) entity;
                UUID uuid = item.getUniqueId();

                // already waiting to be deposited
                if (queuedItems.contains(uuid)) {
                    continue;
                }

                // the bounding box can touch items just outside the block radius
                Location location = item.getLocation();
                if (Math.abs(location.getBlockX() - baseX) > radius
                        || Math.abs(location.getBlockY() - baseY) > radius
                        || Math.abs(location.getBlockZ() - baseZ) > radius) {
                    continue;
                }

                Boolean pull = sweepChecked.get(uuid);
                if (pull == null) {
                    pull = canPull(lwc, item);
                    sweepChecked.put(uuid, pull);
                }

                if (!pull) {
                    continue;
                }

                // never allow a shulker box to enter another shulker box
                if (shulkerBox && item.getItemStack().getType().toString().contains("SHULKER_BOX")) {
                    continue;
                }

                offerNode(item, protection, true);
            }
        }

        /**
         * Queue every item in every world that is within the radius of a magnet protection.
         * Used when not every protection is cached.
         */
        private void queueFromItems(Server server, LWC lwc) {
            for (World world : server.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (!(entity instanceof Item)) {
                        continue;
                    }

                    Item item = (Item) entity;

                    if (!canPull(lwc, item)) {
                        continue;
                    }

                    Location location = item.getLocation();
                    int x = location.getBlockX();
                    int y = location.getBlockY();
                    int z = location.getBlockZ();

                    List<Protection> protections = lwc.getPhysicalDatabase().loadProtections(world.getName(), x, y,
                            z, radius);
                    for (Protection protection : protections) {
                        if (protection.hasFlag(Flag.Type.MAGNET)) {

                            if (protection.getBukkitWorld().getName() != item.getWorld().getName())
                                continue;

                            // we only want inventory blocks
                            if (!(protection.getBlock().getState(false) instanceof InventoryHolder)) {
                                continue;
                            }

                            // never allow a shulker box to enter another shulker box
                            if (item.getItemStack().getType().toString().contains("SHULKER_BOX") && protection.getBlock().getType().toString().contains("SHULKER_BOX")) {
                                continue;
                            }

                            offerNode(item, protection, false);
                            break;
                        }
                    }
                }
            }
        }

        /**
         * Check if an item may be pulled by a magnet (display items, pickup delay,
         * blacklist, validity and {@link LWCMagnetPullEvent}, in that order)
         */
        private boolean canPull(LWC lwc, Item item) {
            if (isDisplay(item)) {
                return false;
            }

            ItemStack stack = item.getItemStack();

            // check if the pickup delay is ok
            if (item.getPickupDelay() > maxPickupDelay) {
                return false;
            }

            // check if it is in the blacklist
            if (itemBlacklist.contains(stack.getType())) {
                return false;
            }

            // check if the item is valid
            if (stack.getAmount() <= 0) {
                return false;
            }

            if (item.isDead()) {
                return false;
            }

            LWCMagnetPullEvent event = new LWCMagnetPullEvent(item);
            lwc.getModuleLoader().dispatchEvent(event);

            // has the event been cancelled?
            return !event.isCancelled();
        }
    }

    public static boolean isDisplay(Entity entity) {
        try {
            if (entity.getType() == ITEM_ENTITY_TYPE) {
                ItemMeta itemMeta = ((Item) entity).getItemStack().getItemMeta();
                if (itemMeta != null && containsLocation(itemMeta.getDisplayName())) {
                    return true;
                }
            } else if (entity.getType() == EntityType.ARMOR_STAND) {
                if (containsLocation(entity.getCustomName())) {
                    return true;
                }
            }
        } catch (NoSuchFieldError error) {
            // do nothing
        }
        return false;
    }

    public static boolean containsLocation(String s) {
        if (s == null)
            return false;
        if (s.startsWith("***{")) {
            if ((s.indexOf(',') != s.lastIndexOf(',')) && s.indexOf('}') != -1)
                return true;
        }
        return false;
    }

    @Override
    public void load(LWC lwc) {
        enabled = configuration.getBoolean("magnet.enabled", false);
        itemBlacklist = new ArrayList<Material>();
        radius = configuration.getInt("magnet.radius", 3);
        perSweep = configuration.getInt("magnet.perSweep", 20);
        maxPickupDelay = configuration.getInt("magnet.maxPickupDelay", 40);

        if (!enabled) {
            return;
        }

        // get the item blacklist
        List<String> temp = configuration.getStringList("magnet.blacklist", new ArrayList<String>());

        for (String item : temp) {
            Material material = Material.matchMaterial(item);

            if (material != null) {
                itemBlacklist.add(material);
            }
        }

        // register our search thread schedule
        MagnetTask searchThread = new MagnetTask();
        // runs every tick; each sweep of SWEEP_TICKS ticks searches every magnet once
        lwc.getPlugin().getServer().getScheduler().scheduleSyncRepeatingTask(lwc.getPlugin(), searchThread, 50, 1);
    }

}
