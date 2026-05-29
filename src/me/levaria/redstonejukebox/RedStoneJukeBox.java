package me.levaria.redstonejukebox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.TileEntity;
import net.minecraft.server.TileEntityRecordPlayer;

import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockListener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class RedStoneJukeBox extends JavaPlugin {
    private static final long GOLD_RECORD_TICKS = 178L * 20L;
    private static final long GREEN_RECORD_TICKS = 185L * 20L;

    private static final BlockFace[] CHECK_FACES = new BlockFace[] {
        BlockFace.NORTH,
        BlockFace.EAST,
        BlockFace.SOUTH,
        BlockFace.WEST,
        BlockFace.UP,
        BlockFace.DOWN
    };

    private final Map<String, JukeboxState> jukeboxes = new HashMap<String, JukeboxState>();
    private final Set<String> queuedChecks = new HashSet<String>();

    private final PlayerListener playerListener = new PlayerListener() {
        public void onPlayerInteract(PlayerInteractEvent event) {
            if (event.isCancelled()) {
                return;
            }

            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.hasBlock()) {
                return;
            }

            Block clicked = event.getClickedBlock();
            if (!isJukebox(clicked)) {
                return;
            }

            scheduleStateRefresh(clicked);
        }
    };

    private final BlockListener blockListener = new BlockListener() {
        public void onBlockRedstoneChange(BlockRedstoneEvent event) {
            scheduleNearbyJukeboxChecks(event.getBlock());
        }

        public void onBlockBreak(BlockBreakEvent event) {
            if (event.isCancelled()) {
                return;
            }

            if (isJukebox(event.getBlock())) {
                removeJukebox(event.getBlock());
                queuedChecks.remove(key(event.getBlock()));
            }
        }
    };

    public void onEnable() {
        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvent(Event.Type.PLAYER_INTERACT, playerListener, Event.Priority.Monitor, this);
        pluginManager.registerEvent(Event.Type.REDSTONE_CHANGE, blockListener, Event.Priority.Monitor, this);
        pluginManager.registerEvent(Event.Type.BLOCK_BREAK, blockListener, Event.Priority.Monitor, this);
    }

    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        jukeboxes.clear();
        queuedChecks.clear();
    }

    private void scheduleNearbyJukeboxChecks(Block source) {
        scheduleJukeboxCheck(source);

        for (int i = 0; i < CHECK_FACES.length; i++) {
            Block adjacent = source.getRelative(CHECK_FACES[i]);
            scheduleJukeboxCheck(adjacent);

            for (int j = 0; j < CHECK_FACES.length; j++) {
                scheduleJukeboxCheck(adjacent.getRelative(CHECK_FACES[j]));
            }
        }
    }

    private void scheduleJukeboxCheck(final Block block) {
        if (!isJukebox(block)) {
            return;
        }

        final String key = key(block);
        if (!queuedChecks.add(key)) {
            return;
        }

        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                queuedChecks.remove(key);
                checkRedstoneToggle(block);
            }
        }, 1L);
    }

    private void scheduleStateRefresh(final Block block) {
        getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                refreshJukeboxState(block);
            }
        }, 1L);
    }

    private void checkRedstoneToggle(Block block) {
        if (!isJukebox(block)) {
            removeJukebox(block);
            return;
        }

        int recordId = getRecordId(block);
        if (!isRecordId(recordId)) {
            removeJukebox(block);
            return;
        }

        String key = key(block);
        JukeboxState state = jukeboxes.get(key);
        boolean powered = isPowered(block);

        if (state == null) {
            state = new JukeboxState(recordId, powered, true);
            jukeboxes.put(key, state);
            scheduleMusicEnd(key, state);
            return;
        }

        if (state.recordId != recordId) {
            state.recordId = recordId;
            state.playing = true;
            scheduleMusicEnd(key, state);
        }

        if (powered && !state.powered) {
            toggleMusic(block, state);
        }

        state.powered = powered;
    }

    private void refreshJukeboxState(Block block) {
        if (!isJukebox(block)) {
            removeJukebox(block);
            return;
        }

        int recordId = getRecordId(block);
        String key = key(block);

        if (!isRecordId(recordId)) {
            removeJukebox(key);
            return;
        }

        boolean powered = isPowered(block);
        JukeboxState state = jukeboxes.get(key);

        if (state == null) {
            state = new JukeboxState(recordId, powered, true);
            jukeboxes.put(key, state);
            scheduleMusicEnd(key, state);
            return;
        }

        if (state.recordId != recordId || !state.playing) {
            state.recordId = recordId;
            state.playing = true;
            scheduleMusicEnd(key, state);
        }

        state.powered = powered;
    }

    private void toggleMusic(Block block, JukeboxState state) {
        if (state.playing) {
            playRecordEffect(block, 0);
            cancelMusicEnd(state);
            state.playing = false;
        } else {
            playRecordEffect(block, state.recordId);
            state.playing = true;
            scheduleMusicEnd(key(block), state);
        }
    }

    private void scheduleMusicEnd(final String key, final JukeboxState state) {
        cancelMusicEnd(state);

        long duration = getRecordDurationTicks(state.recordId);
        if (duration <= 0L) {
            return;
        }

        state.endTaskId = getServer().getScheduler().scheduleSyncDelayedTask(this, new Runnable() {
            public void run() {
                JukeboxState currentState = jukeboxes.get(key);

                if (currentState == state) {
                    state.playing = false;
                    state.endTaskId = -1;
                }
            }
        }, duration);
    }

    private void cancelMusicEnd(JukeboxState state) {
        if (state.endTaskId != -1) {
            getServer().getScheduler().cancelTask(state.endTaskId);
            state.endTaskId = -1;
        }
    }

    private void removeJukebox(Block block) {
        removeJukebox(key(block));
    }

    private void removeJukebox(String key) {
        JukeboxState state = jukeboxes.remove(key);

        if (state != null) {
            cancelMusicEnd(state);
        }
    }

    private void playRecordEffect(Block block, int recordId) {
        World world = block.getWorld();
        world.playEffect(block.getLocation(), Effect.RECORD_PLAY, recordId);
    }

    private int getRecordId(Block block) {
        if (!isJukebox(block) || block.getData() == 0) {
            return 0;
        }

        try {
            CraftWorld craftWorld = (CraftWorld) block.getWorld();
            TileEntity tileEntity = craftWorld.getTileEntityAt(block.getX(), block.getY(), block.getZ());

            if (tileEntity instanceof TileEntityRecordPlayer) {
                return ((TileEntityRecordPlayer) tileEntity).a;
            }
        } catch (RuntimeException exception) {
            getServer().getLogger().warning("[RedStoneJukeBox] Could not read jukebox record at "
                    + key(block) + ": " + exception.getMessage());
        }

        return getRecordIdFromData(block);
    }

    private int getRecordIdFromData(Block block) {
        byte data = block.getData();

        if (data == 1) {
            return Material.GOLD_RECORD.getId();
        }

        if (data == 2) {
            return Material.GREEN_RECORD.getId();
        }

        return 0;
    }

    private boolean isPowered(Block block) {
        return block.isBlockPowered() || block.isBlockIndirectlyPowered();
    }

    private boolean isJukebox(Block block) {
        return block != null && block.getType() == Material.JUKEBOX;
    }

    private boolean isRecordId(int recordId) {
        return recordId == Material.GOLD_RECORD.getId() || recordId == Material.GREEN_RECORD.getId();
    }

    private long getRecordDurationTicks(int recordId) {
        if (recordId == Material.GOLD_RECORD.getId()) {
            return GOLD_RECORD_TICKS;
        }

        if (recordId == Material.GREEN_RECORD.getId()) {
            return GREEN_RECORD_TICKS;
        }

        return 0L;
    }

    private String key(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static final class JukeboxState {
        private int recordId;
        private boolean powered;
        private boolean playing;
        private int endTaskId = -1;

        private JukeboxState(int recordId, boolean powered, boolean playing) {
            this.recordId = recordId;
            this.powered = powered;
            this.playing = playing;
        }
    }
}
