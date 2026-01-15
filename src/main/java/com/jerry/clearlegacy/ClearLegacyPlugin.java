package com.yourname.clearlegacy;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClearLegacyPlugin extends JavaPlugin {

    // 处于清理模式的管理员
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    private int scanIntervalTicks;
    private int chunkRadius; // -1 => server view-distance
    private boolean clearItemFrames;
    private boolean clearInventoryHolderEntities;
    private boolean logStats;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocalConfig();

        // 定时扫描：只扫描开启清理模式的玩家
        Bukkit.getScheduler().runTaskTimer(this, this::scanAllEnabledPlayers, scanIntervalTicks, scanIntervalTicks);

        getLogger().info("ClearLegacy enabled.");
    }

    @Override
    public void onDisable() {
        enabled.clear();
    }

    private void reloadLocalConfig() {
        scanIntervalTicks = Math.max(1, getConfig().getInt("scan-interval-ticks", 20));
        chunkRadius = getConfig().getInt("chunk-radius", -1);
        clearItemFrames = getConfig().getBoolean("clear-item-frames", true);
        clearInventoryHolderEntities = getConfig().getBoolean("clear-inventory-holder-entities", true);
        logStats = getConfig().getBoolean("log-stats", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("clearlegacy")) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        if (!player.hasPermission("clearlegacy.use")) {
            player.sendMessage("你没有权限使用该命令。");
            return true;
        }

        UUID id = player.getUniqueId();
        if (enabled.contains(id)) {
            enabled.remove(id);
            player.sendMessage("§a[ClearLegacy] 清理模式已关闭。");
        } else {
            enabled.add(id);
            player.sendMessage("§c[ClearLegacy] 清理模式已开启：你加载距离内的所有容器与展示框将被自动清空。");
        }
        return true;
    }

    private void scanAllEnabledPlayers() {
        if (enabled.isEmpty()) return;

        for (UUID uuid : enabled) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            // 如果他不再有权限，也自动关掉
            if (!p.hasPermission("clearlegacy.use")) {
                enabled.remove(uuid);
                continue;
            }

            scanPlayerLoadedChunks(p);
        }
    }

    private void scanPlayerLoadedChunks(Player player) {
        World w = player.getWorld();

        int radius = chunkRadius;
        if (radius < 0) {
            radius = Bukkit.getServer().getViewDistance();
        }
        if (radius < 0) radius = 10; // 极端兜底

        int centerX = player.getLocation().getChunk().getX();
        int centerZ = player.getLocation().getChunk().getZ();

        int clearedContainers = 0;
        int clearedFrames = 0;
        int clearedEntityInventories = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;

                if (!w.isChunkLoaded(cx, cz)) continue;
                Chunk chunk = w.getChunkAt(cx, cz);

                // 1) 清方块容器（tile entities）
                // Paper 的 Chunk#getTileEntities()
                BlockState[] tiles;
                try {
                    tiles = chunk.getTileEntities();
                } catch (Throwable t) {
                    // 如果该 API 不存在，就跳过容器清理（避免崩服）
                    tiles = new BlockState[0];
                }

                for (BlockState state : tiles) {
                    if (state instanceof Container container) {
                        Inventory inv = container.getInventory();
                        if (!isInventoryEmpty(inv)) {
                            inv.clear();
                            clearedContainers++;
                        }
                    }
                }

                // 2) 清实体（展示框、带库存实体）
                for (Entity e : chunk.getEntities()) {
                    if (clearItemFrames && (e instanceof ItemFrame || e instanceof GlowItemFrame)) {
                        ItemFrame frame = (ItemFrame) e;
                        ItemStack item = frame.getItem();
                        if (item != null && item.getType().isItem()) {
                            // Paper/Spigot: 清空展示物
                            frame.setItem(null);
                            clearedFrames++;
                        }
                        continue;
                    }

                    if (clearInventoryHolderEntities && e instanceof InventoryHolder holder) {
                        //ChestMinecart / HopperMinecart 等
                        Inventory inv = holder.getInventory();
                        if (inv != null && !isInventoryEmpty(inv)) {
                            inv.clear();
                            clearedEntityInventories++;
                        }
                    }
                }
            }
        }

        if (logStats && (clearedContainers + clearedFrames + clearedEntityInventories) > 0) {
            getLogger().info("[ClearLegacy] " + player.getName()
                    + " cleared: containers=" + clearedContainers
                    + ", itemFrames=" + clearedFrames
                    + ", entityInventories=" + clearedEntityInventories);
        }
    }

    private boolean isInventoryEmpty(Inventory inv) {
        if (inv == null) return true;
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir() && it.getAmount() > 0) return false;
        }
        return true;
    }
}
