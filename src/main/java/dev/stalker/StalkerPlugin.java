package dev.stalker;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

public final class StalkerPlugin extends JavaPlugin implements TabExecutor {

    private final Random rng = new Random();
    private final Map<UUID, Long> nextRoll = new HashMap<>();
    private final Set<UUID> hasFigure = new HashSet<>();
    private final Set<Mannequin> figures = new HashSet<>();
    private final Map<UUID, Map<Location, BlockData>> fakedTorches = new HashMap<>();
    private BukkitTask loop;

    // ───────────────────────── lifecycle ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var cmd = getCommand("stalker");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        startLoop();
    }

    @Override
    public void onDisable() {
        if (loop != null) loop.cancel();
        for (Mannequin m : new ArrayList<>(figures)) m.remove();
        figures.clear();
        for (var e : fakedTorches.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (var t : e.getValue().entrySet()) p.sendBlockChange(t.getKey(), t.getKey().getBlock().getBlockData());
        }
        fakedTorches.clear();
    }

    private void startLoop() {
        if (loop != null) loop.cancel();
        int interval = Math.max(1, getConfig().getInt("check-interval-ticks", 40));
        loop = Bukkit.getScheduler().runTaskTimer(this, this::tick, interval, interval);
    }

    // ───────────────────────── main loop ─────────────────────────

    private void tick() {
        if (!getConfig().getBoolean("enabled", true)) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!eligible(p, true)) { nextRoll.remove(p.getUniqueId()); continue; }
            Long next = nextRoll.get(p.getUniqueId());
            if (next == null) {
                nextRoll.put(p.getUniqueId(), now + secs("first-roll-delay-seconds", 30, 120));
                continue;
            }
            if (now < next) continue;
            nextRoll.put(p.getUniqueId(), now + secs("cooldown-seconds", 90, 300));
            String pick = pickEvent();
            if (pick != null) run(pick, p);
        }
    }

    private boolean eligible(Player p, boolean checkNight) {
        var cfg = getConfig();
        if (!p.isOnline() || p.isDead()) return false;
        String perm = cfg.getString("immune-permission", "stalker.immune");
        if (perm != null && !perm.isEmpty() && p.hasPermission(perm)) return false;
        for (String g : cfg.getStringList("ignore-gamemodes"))
            if (p.getGameMode().name().equalsIgnoreCase(g)) return false;
        World w = p.getWorld();
        List<String> worlds = cfg.getStringList("worlds");
        if (worlds.isEmpty()) { if (w.getEnvironment() != World.Environment.NORMAL) return false; }
        else if (!worlds.contains(w.getName())) return false;
        if (checkNight && cfg.getBoolean("night-only", true)) {
            long t = w.getTime();
            long s = cfg.getLong("night-start", 13000), e = cfg.getLong("night-end", 23000);
            boolean night = s <= e ? (t >= s && t <= e) : (t >= s || t <= e);
            if (!night) return false;
        }
        return true;
    }

    private String pickEvent() {
        ConfigurationSection w = getConfig().getConfigurationSection("weights");
        if (w == null) return null;
        double total = 0;
        for (String k : w.getKeys(false)) total += Math.max(0, w.getDouble(k));
        if (total <= 0) return null;
        double r = rng.nextDouble() * total;
        for (String k : w.getKeys(false)) {
            r -= Math.max(0, w.getDouble(k));
            if (r < 0) return k.equalsIgnoreCase("none") ? null : k.toLowerCase();
        }
        return null;
    }

    private void run(String event, Player p) {
        ConfigurationSection c = getConfig().getConfigurationSection("events." + event);
        if (c == null) return;
        switch (event) {
            case "footsteps" -> footsteps(p, c);
            case "ambient" -> ambient(p, c);
            case "figure" -> figure(p, c);
            case "torches" -> torches(p, c);
        }
    }

    // ───────────────────────── footsteps ─────────────────────────

    private void footsteps(Player p, ConfigurationSection c) {
        int steps = (int) Math.round(between(c, "steps", 4, 12));
        double startDist = between(c, "start-distance", 8, 16);
        double stopDist = c.getDouble("stop-distance", 3.0);
        double approach = c.getDouble("approach-per-step", 0.8);
        int interval = Math.max(1, c.getInt("step-interval-ticks", 9));
        double spread = c.getDouble("behind-spread-degrees", 50);
        boolean behind = c.getBoolean("behind-player", true);
        double angle = behind
                ? p.getLocation().getYaw() + 180 + (rng.nextDouble() * 2 - 1) * spread
                : rng.nextDouble() * 360;

        new BukkitRunnable() {
            int i = 0;
            double d = startDist;
            @Override public void run() {
                if (!p.isOnline() || i >= steps) { cancel(); return; }
                Location pl = p.getLocation();
                double rad = Math.toRadians(angle);
                Location g = ground(pl.getWorld(), pl.getX() - Math.sin(rad) * d, pl.getZ() + Math.cos(rad) * d, pl.getBlockY());
                if (g != null) {
                    Block under = g.getBlock().getRelative(0, -1, 0);
                    Sound s = under.getBlockData().getSoundGroup().getStepSound();
                    float vol = (float) between(c, "volume", 0.5, 0.9);
                    float pitch = (float) between(c, "pitch", 0.8, 1.0);
                    p.playSound(g, s, SoundCategory.PLAYERS, vol, pitch);
                }
                d = Math.max(stopDist, d - approach);
                i++;
            }
        }.runTaskTimer(this, 0L, interval);
    }

    // ───────────────────────── ambient sounds ─────────────────────────

    private void ambient(Player p, ConfigurationSection c) {
        List<String> raw = c.getStringList("sounds");
        List<String> keys = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (String line : raw) {
            String key = line; double wt = 1;
            int idx = line.lastIndexOf(':');
            if (idx > 0) {
                try { wt = Double.parseDouble(line.substring(idx + 1)); key = line.substring(0, idx); }
                catch (NumberFormatException ignored) { }
            }
            if (wt <= 0) continue;
            keys.add(key); weights.add(wt); total += wt;
        }
        if (keys.isEmpty()) return;
        int count = (int) Math.round(between(c, "count", 1, 2));
        long delay = 0;
        for (int n = 0; n < count; n++) {
            double r = rng.nextDouble() * total;
            String chosen = keys.get(0);
            for (int k = 0; k < keys.size(); k++) { r -= weights.get(k); if (r < 0) { chosen = keys.get(k); break; } }
            final String sound = chosen;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                double dist = between(c, "distance", 6, 20);
                double rad = Math.toRadians(rng.nextDouble() * 360);
                Location pl = p.getLocation();
                Location at = pl.clone().add(-Math.sin(rad) * dist, rng.nextDouble() * 3 - 1, Math.cos(rad) * dist);
                p.playSound(at, sound, SoundCategory.AMBIENT,
                        (float) between(c, "volume", 0.4, 1.0), (float) between(c, "pitch", 0.6, 1.0));
            }, delay);
            delay += (long) between(c, "gap-ticks", 20, 100);
        }
    }

    // ───────────────────────── distant figure ─────────────────────────

    private void figure(Player p, ConfigurationSection c) {
        if (hasFigure.contains(p.getUniqueId())) return;
        Location pl = p.getLocation();
        double dist = between(c, "distance", 22, 40);
        double off = between(c, "angle-degrees", 25, 85) * (rng.nextBoolean() ? 1 : -1);
        double rad = Math.toRadians(pl.getYaw() + off);
        Location g = ground(pl.getWorld(), pl.getX() - Math.sin(rad) * dist, pl.getZ() + Math.cos(rad) * dist, pl.getBlockY());
        if (g == null) return;
        if (c.getBoolean("require-open-sky-nearby", false) && g.getWorld().getHighestBlockYAt(g) > g.getBlockY() + 1) return;

        Mannequin m = g.getWorld().spawn(g, Mannequin.class, e -> {
            e.setVisibleByDefault(false);   // nobody sees it unless we say so
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setImmovable(true);
            e.setCustomNameVisible(false);
            e.setDescription(null);         // no "NPC" label under it
        });
        p.showEntity(this, m);              // ...except this one player
        figures.add(m);
        hasFigure.add(p.getUniqueId());

        int maxTicks = c.getInt("max-seconds", 25) * 20;
        double vanishDist = c.getDouble("vanish-distance", 12);
        boolean vanishOnLook = c.getBoolean("vanish-on-look", true);
        double lookCos = Math.cos(Math.toRadians(c.getDouble("look-angle-degrees", 10)));
        int lookNeeded = c.getInt("look-ticks", 12);
        boolean face = c.getBoolean("face-player", true);

        new BukkitRunnable() {
            int age = 0, look = 0;
            void end() { m.remove(); figures.remove(m); hasFigure.remove(p.getUniqueId()); cancel(); }
            @Override public void run() {
                if (!p.isOnline() || !m.isValid() || p.getWorld() != m.getWorld()) { end(); return; }
                age += 2;
                if (age > maxTicks) { end(); return; }
                Location eye = p.getEyeLocation();
                Vector to = m.getEyeLocation().toVector().subtract(eye.toVector());
                double d = to.length();
                if (d < vanishDist) { end(); return; }
                if (vanishOnLook) {
                    if (eye.getDirection().dot(to.clone().normalize()) > lookCos) {
                        look += 2;
                        if (look >= lookNeeded) { end(); return; }
                    } else look = 0;
                }
                if (face) {
                    Location ml = m.getLocation();
                    ml.setDirection(eye.toVector().subtract(m.getEyeLocation().toVector()));
                    m.setRotation(ml.getYaw(), ml.getPitch());
                }
            }
        }.runTaskTimer(this, 2L, 2L);
    }

    // ───────────────────────── torches → redstone torches (client-side) ─────────────────────────

    private void torches(Player p, ConfigurationSection c) {
        int radius = c.getInt("radius", 12);
        boolean soul = c.getBoolean("include-soul-torches", true);
        boolean lit = c.getBoolean("redstone-lit", false);
        int stagger = Math.max(0, c.getInt("stagger-ticks", 10));
        World w = p.getWorld();
        Location pl = p.getLocation();
        int px = pl.getBlockX(), py = pl.getBlockY(), pz = pl.getBlockZ();

        List<Block> found = new ArrayList<>();
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = py - radius; y <= py + radius; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t == Material.TORCH || t == Material.WALL_TORCH
                            || (soul && (t == Material.SOUL_TORCH || t == Material.SOUL_WALL_TORCH)))
                        found.add(b);
                }
            }
        }
        if (found.isEmpty()) return;
        Collections.shuffle(found, rng);
        int amount = Math.min(found.size(), (int) Math.round(between(c, "amount", 2, 6)));

        Map<Location, BlockData> store = fakedTorches.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        for (int i = 0; i < amount; i++) {
            Block b = found.get(i);
            Location loc = b.getLocation();
            BlockData orig = b.getBlockData();
            boolean wall = b.getType() == Material.WALL_TORCH || b.getType() == Material.SOUL_WALL_TORCH;
            BlockData fake = (wall ? Material.REDSTONE_WALL_TORCH : Material.REDSTONE_TORCH).createBlockData();
            if (wall && orig instanceof Directional od && fake instanceof Directional fd) fd.setFacing(od.getFacing());
            if (fake instanceof Lightable l) l.setLit(lit);

            long on = (long) i * stagger;
            long off = on + (long) (between(c, "revert-seconds", 8, 30) * 20);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                store.put(loc, orig);
                p.sendBlockChange(loc, fake);
            }, on);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                store.remove(loc);
                if (p.isOnline()) p.sendBlockChange(loc, loc.getBlock().getBlockData());
            }, off);
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private Location ground(World w, double x, double z, int refY) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null;
        for (int y = refY + 4; y >= refY - 6; y--) {
            Block b = w.getBlockAt(bx, y, bz);
            if (b.getType().isSolid()
                    && w.getBlockAt(bx, y + 1, bz).isPassable()
                    && w.getBlockAt(bx, y + 2, bz).isPassable())
                return new Location(w, bx + 0.5, y + 1, bz + 0.5);
        }
        return null;
    }

    /** Reads section.min / section.max (or a plain number) and returns a random value between. */
    private double between(ConfigurationSection c, String path, double defMin, double defMax) {
        double a, b;
        if (c.isConfigurationSection(path)) {
            a = c.getDouble(path + ".min", defMin);
            b = c.getDouble(path + ".max", defMax);
        } else if (c.isSet(path)) { a = b = c.getDouble(path); }
        else { a = defMin; b = defMax; }
        if (b < a) { double t = a; a = b; b = t; }
        return a + rng.nextDouble() * (b - a);
    }

    private long secs(String path, double defMin, double defMax) {
        return (long) (between(getConfig(), path, defMin, defMax) * 1000);
    }

    // ───────────────────────── admin command (replies go to the sender only) ─────────────────────────

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) return false;
        if (a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            startLoop();
            nextRoll.clear();
            s.sendMessage("Stalker config reloaded.");
            return true;
        }
        if (a[0].equalsIgnoreCase("test") && a.length > 1 && s instanceof Player p) {
            run(a[1].toLowerCase(), p);
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (a.length == 1) return List.of("reload", "test");
        if (a.length == 2 && a[0].equalsIgnoreCase("test")) return List.of("footsteps", "ambient", "figure", "torches");
        return List.of();
    }
}
