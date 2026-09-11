package me.bogeyman.stonepowers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

public final class Main extends org.bukkit.plugin.java.JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String PREFIX = "§8[§6StonePowers§8] §r";
    private static final int DEFAULT_START_HEARTS = 10;
    private static final int DEFAULT_MIN_HEARTS = 1;
    private static final int DEFAULT_MAX_HEARTS = 20;

    private final Random random = new Random();
    private final Map<UUID, EnumMap<AbilitySlot, Long>> cooldowns = new HashMap<>();
    private NamespacedKey stoneKey;
    private NamespacedKey heartsKey;
    private NamespacedKey initializedKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stoneKey = new NamespacedKey(this, "stone_type");
        heartsKey = new NamespacedKey(this, "hearts");
        initializedKey = new NamespacedKey(this, "initialized");
        getServer().getPluginManager().registerEvents(this, this);
        Command command = Objects.requireNonNull(getCommand("stone"), "stone command missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
        for (Player player : getServer().getOnlinePlayers()) {
            initializePlayer(player);
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) applyPassiveEffects(player);
        }, 20L, 20L);
        getLogger().info("StonePowers enabled with 8 stones.");
    }

    @Override
    public void onDisable() {
        cooldowns.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        initializePlayer(event.getPlayer());
    }

    private void initializePlayer(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (!pdc.has(heartsKey, PersistentDataType.INTEGER)) {
            setHearts(player, getConfig().getInt("start-hearts", DEFAULT_START_HEARTS));
        } else {
            applyMaxHealth(player);
        }
        if (!pdc.has(initializedKey, PersistentDataType.BYTE)) {
            giveStone(player, randomStone(), true);
            pdc.set(initializedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == null || event.getHand().name().equals("OFF_HAND")) return;
        Player player = event.getPlayer();
        StoneType stone = getStoneType(event.getItem());
        if (stone == null) return;
        event.setCancelled(true);
        AbilitySlot slot = player.isSneaking() ? AbilitySlot.SECOND : AbilitySlot.FIRST;
        long cooldown = cooldownSeconds(slot);
        long now = System.currentTimeMillis();
        EnumMap<AbilitySlot, Long> map = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(AbilitySlot.class));
        long readyAt = map.getOrDefault(slot, 0L);
        if (readyAt > now) {
            long seconds = (long) Math.ceil((readyAt - now) / 1000.0);
            player.sendActionBar("§cAbility on cooldown: " + seconds + "s");
            return;
        }
        map.put(slot, now + cooldown * 1000L);
        activate(player, stone, slot);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) player.sendActionBar("§aAbility Ready!");
        }, cooldown * 20L);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;

        int min = getConfig().getInt("min-hearts", DEFAULT_MIN_HEARTS);
        int max = getConfig().getInt("max-hearts", DEFAULT_MAX_HEARTS);
        int gain = Math.max(0, getConfig().getInt("killer-heart-gain", 1));
        int loss = Math.max(0, getConfig().getInt("victim-heart-loss", 1));
        setHearts(killer, Math.min(max, getHearts(killer) + gain));
        setHearts(victim, Math.max(min, getHearts(victim) - loss));

        StoneType transferred = getOwnedStone(victim);
        if (transferred != null) {
            removeStoneItems(victim.getInventory());
            removeStoneItems(killer.getInventory());
            victim.getPersistentDataContainer().remove(stoneKey);
            giveStone(killer, transferred, true);
            killer.sendMessage("§6§lSTONE TRANSFER");
            killer.sendMessage("§f" + victim.getName() + "'s §e" + transferred.displayName + " §fhas been transferred to you!");
            victim.sendMessage("§cYour " + transferred.displayName + " §chas been transferred to " + killer.getName() + ".");
        }
    }

    @EventHandler
    public void onOwnerImmune(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        StoneType stone = getOwnedStone(player);
        if (stone == StoneType.FIRE && event.getCause() == EntityDamageEvent.DamageCause.FIRE) event.setCancelled(true);
        if (stone == StoneType.FIRE && event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK) event.setCancelled(true);
        if (stone == StoneType.FIRE && event.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR) event.setCancelled(true);
        if (stone == StoneType.LIGHTNING && event.getCause() == EntityDamageEvent.DamageCause.LIGHTNING) event.setCancelled(true);
        if (stone == StoneType.WITHER && event.getCause() == EntityDamageEvent.DamageCause.WITHER) event.setCancelled(true);
        if (stone == StoneType.ICE && event.getCause() == EntityDamageEvent.DamageCause.FREEZE) event.setDamage(event.getDamage() * 0.25);
    }

    @EventHandler
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        StoneType stone = getStoneType(player.getInventory().getItemInMainHand());
        if (stone == StoneType.FIRE && event.getEntity() instanceof LivingEntity target) {
            target.setFireTicks(Math.max(target.getFireTicks(), 50));
        }
    }

    private void applyPassiveEffects(Player player) {
        StoneType stone = getOwnedStone(player);
        if (stone == null) return;
        switch (stone) {
            case FIRE -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 50, 0, false, false, false));
            }
            case ICE -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 50, 0, false, false, false));
                if (player.getFreezeTicks() > 0) player.setFreezeTicks(Math.max(0, player.getFreezeTicks() - 20));
            }
            case ENDER -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 50, 0, false, false, false));
            }
            case LIGHTNING -> player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 1, false, false, false));
            case WATER -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 50, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 50, 0, false, false, false));
            }
            case EARTH -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 50, 1, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 50, 0, false, false, false));
            }
            case WIND -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 1, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 50, 1, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 50, 0, false, false, false));
            }
            case WITHER -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 50, 0, false, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 50, 0, false, false, false));
            }
        }
    }

    private void activate(Player player, StoneType stone, AbilitySlot slot) {
        switch (stone) {
            case FIRE -> fireAbility(player, slot);
            case ICE -> iceAbility(player, slot);
            case ENDER -> enderAbility(player, slot);
            case LIGHTNING -> lightningAbility(player, slot);
            case WATER -> waterAbility(player, slot);
            case EARTH -> earthAbility(player, slot);
            case WIND -> windAbility(player, slot);
            case WITHER -> witherAbility(player, slot);
        }
    }

    private void fireAbility(Player player, AbilitySlot slot) {
        Location center = player.getLocation().clone();
        if (slot == AbilitySlot.FIRST) {
            particleSphere(center, Particle.FLAME, 3.5, 50);
            player.getWorld().playSound(center, Sound.ENTITY_BLAZE_SHOOT, 1.2f, 0.8f);
            damageNearby(player, center, 4.0, 7.0, 80, 40);
            igniteNearby(player, center, 4.0, 60);
            player.sendActionBar("§6Flame Burst §8— §a12s cooldown");
        } else {
            Location target = forwardLocation(player, 8.0);
            particleSphere(target, Particle.FLAME, 3.0, 70);
            player.getWorld().spawnParticle(Particle.EXPLOSION, target, 1);
            player.getWorld().playSound(target, Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.8f);
            damageNearby(player, target, 4.5, 12.0, 100, 50);
            igniteNearby(player, target, 4.5, 100);
            player.sendActionBar("§cMeteor Strike §8— §a25s cooldown");
        }
    }

    private void iceAbility(Player player, AbilitySlot slot) {
        Location center = player.getLocation().clone();
        if (slot == AbilitySlot.FIRST) {
            particleSphere(center, Particle.SNOWFLAKE, 4.0, 60);
            player.getWorld().playSound(center, Sound.BLOCK_POWDER_SNOW_STEP, 1.2f, 0.7f);
            effectNearby(player, center, 4.0, new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
            damageNearby(player, center, 4.0, 5.0, 80, 40);
            player.sendActionBar("§bFrost Wave §8— §a12s cooldown");
        } else {
            particleSphere(center, Particle.SNOWFLAKE, 3.0, 100);
            player.getWorld().playSound(center, Sound.BLOCK_GLASS_BREAK, 1.1f, 0.6f);
            effectNearby(player, center, 4.0, new PotionEffect(PotionEffectType.SLOWNESS, 140, 4));
            effectNearby(player, center, 4.0, new PotionEffect(PotionEffectType.MINING_FATIGUE, 100, 1));
            for (Entity entity : center.getWorld().getNearbyEntities(center, 4.0, 4.0, 4.0)) {
                if (!(entity instanceof LivingEntity target) || entity.equals(player)) continue;
                target.setFreezeTicks(Math.max(target.getFreezeTicks(), 140));
            }
            damageNearby(player, center, 4.0, 4.0, 80, 40);
            player.sendActionBar("§bIce Prison §8— §a25s cooldown");
        }
    }

    private void enderAbility(Player player, AbilitySlot slot) {
        if (slot == AbilitySlot.FIRST) {
            Location target = forwardLocation(player, 8.0);
            target.setY(target.getY() + 0.1);
            if (!safeForTeleport(target)) target = player.getLocation().clone();
            player.teleport(target);
            player.getWorld().spawnParticle(Particle.PORTAL, target, 80, 0.5, 0.7, 0.5, 0.2);
            player.getWorld().playSound(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.1f);
            player.sendActionBar("§5Ender Dash §8— §a12s cooldown");
        } else {
            Location center = player.getLocation().clone();
            player.getWorld().spawnParticle(Particle.PORTAL, center, 120, 4, 1, 4, 0.4);
            player.getWorld().playSound(center, Sound.BLOCK_PORTAL_AMBIENT, 1.1f, 0.8f);
            for (Entity entity : center.getWorld().getNearbyEntities(center, 5, 3, 5)) {
                if (!(entity instanceof LivingEntity target) || entity.equals(player)) continue;
                Vector pull = center.toVector().subtract(target.getLocation().toVector()).normalize().multiply(0.9);
                target.setVelocity(pull.setY(0.35));
                target.damage(7.0, player);
            }
            player.sendActionBar("§5Void Pull §8— §a25s cooldown");
        }
    }

    private void lightningAbility(Player player, AbilitySlot slot) {
        Location center = player.getLocation().clone();
        if (slot == AbilitySlot.FIRST) {
            player.getWorld().strikeLightningEffect(center);
            player.getWorld().playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 1.0f);
            damageNearby(player, center, 4.0, 8.0, 100, 45);
            player.sendActionBar("§eThunder Strike §8— §a12s cooldown");
        } else {
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, center, 130, 5, 2, 5, 0.3);
            new BukkitRunnable() {
                int ticks = 0;
                @Override public void run() {
                    if (!player.isOnline() || ticks++ >= 8) { cancel(); return; }
                    for (Entity entity : center.getWorld().getNearbyEntities(center, 5, 3, 5)) {
                        if (!(entity instanceof LivingEntity target) || entity.equals(player)) continue;
                        Location strike = target.getLocation();
                        strike.getWorld().strikeLightningEffect(strike);
                        target.damage(4.0, player);
                    }
                }
            }.runTaskTimer(this, 0L, 10L);
            player.sendActionBar("§eStorm Field §8— §a25s cooldown");
        }
    }

    private void waterAbility(Player player, AbilitySlot slot) {
        Location center = slot == AbilitySlot.FIRST ? forwardLocation(player, 5.0) : player.getLocation().clone();
        if (slot == AbilitySlot.FIRST) {
            player.getWorld().spawnParticle(Particle.SPLASH, center, 100, 1.5, 1.2, 1.5, 0.6);
            player.getWorld().playSound(center, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0f, 0.8f);
            knockbackNearby(player, center, 4.0, 1.0, 0.45, 5.0);
            damageNearby(player, center, 3.5, 6.0, 80, 35);
            player.sendActionBar("§bWater Blast §8— §a12s cooldown");
        } else {
            player.getWorld().spawnParticle(Particle.SPLASH, center, 180, 5, 1.5, 5, 0.8);
            player.getWorld().playSound(center, Sound.ENTITY_GENERIC_SPLASH, 1.2f, 0.6f);
            knockbackNearby(player, center, 6.0, 1.3, 0.65, 9.0);
            damageNearby(player, center, 6.0, 5.0, 80, 25);
            player.sendActionBar("§3Tsunami §8— §a25s cooldown");
        }
    }

    private void earthAbility(Player player, AbilitySlot slot) {
        Location center = player.getLocation().clone();
        if (slot == AbilitySlot.FIRST) {
            ringParticles(center, Particle.BLOCK, Material.STONE.createBlockData(), 4.5);
            player.getWorld().playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.65f);
            knockbackNearby(player, center, 4.5, 0.8, 0.8, 6.0);
            damageNearby(player, center, 4.5, 8.0, 100, 45);
            player.sendActionBar("§6Earthquake §8— §a12s cooldown");
        } else {
            ringParticles(center, Particle.BLOCK, Material.COBBLESTONE.createBlockData(), 3.5);
            player.getWorld().playSound(center, Sound.BLOCK_STONE_PLACE, 1.2f, 0.6f);
            player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 160, 3));
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 160, 3));
            player.getWorld().spawnParticle(Particle.BLOCK, center, 100, 1, 1, 1, 0.3, Material.COBBLESTONE.createBlockData());
            player.sendActionBar("§6Earth Shield §8— §a25s cooldown");
        }
    }

    private void windAbility(Player player, AbilitySlot slot) {
        Location center = player.getLocation().clone();
        if (slot == AbilitySlot.FIRST) {
            Vector v = player.getLocation().getDirection().normalize().multiply(1.5).setY(0.7);
            player.setVelocity(v);
            player.getWorld().spawnParticle(Particle.CLOUD, center, 80, 1, 0.7, 1, 0.3);
            player.getWorld().playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1.0f, 1.2f);
            player.sendActionBar("§fWind Dash §8— §a12s cooldown");
        } else {
            player.getWorld().spawnParticle(Particle.CLOUD, center, 160, 5, 1.5, 5, 0.25);
            player.getWorld().playSound(center, Sound.ENTITY_BREEZE_WIND_BURST, 1.3f, 0.8f);
            for (Entity entity : center.getWorld().getNearbyEntities(center, 5, 3, 5)) {
                if (!(entity instanceof LivingEntity target) || entity.equals(player)) continue;
                Vector direction = target.getLocation().toVector().subtract(center.toVector()).normalize();
                target.setVelocity(direction.multiply(1.25).setY(1.0));
                target.damage(5.0, player);
            }
            player.sendActionBar("§fTornado §8— §a25s cooldown");
        }
    }

    private void witherAbility(Player player, AbilitySlot slot) {
        Location center = slot == AbilitySlot.FIRST ? player.getLocation().clone() : forwardLocation(player, 5.0);
        if (slot == AbilitySlot.FIRST) {
            player.getWorld().spawnParticle(Particle.SOUL, center, 100, 3, 1.2, 3, 0.08);
            player.getWorld().spawnParticle(Particle.SMOKE, center, 80, 3, 1.0, 3, 0.04);
            player.getWorld().playSound(center, Sound.ENTITY_WITHER_SHOOT, 1.1f, 0.9f);
            effectNearby(player, center, 4.0, new PotionEffect(PotionEffectType.WITHER, 80, 0));
            damageNearby(player, center, 4.0, 7.0, 90, 45);
            player.sendActionBar("§8Wither Blast §8— §a12s cooldown");
        } else {
            player.getWorld().spawnParticle(Particle.SOUL, center, 220, 6, 2, 6, 0.12);
            player.getWorld().spawnParticle(Particle.SMOKE, center, 180, 6, 2, 6, 0.06);
            player.getWorld().playSound(center, Sound.ENTITY_WITHER_AMBIENT, 1.3f, 0.7f);
            effectNearby(player, center, 6.0, new PotionEffect(PotionEffectType.WITHER, 120, 2));
            effectNearby(player, center, 6.0, new PotionEffect(PotionEffectType.WEAKNESS, 120, 1));
            damageNearby(player, center, 6.0, 10.0, 100, 60);
            player.sendActionBar("§8Wither Storm §8— §a25s cooldown");
        }
    }

    private void damageNearby(Player owner, Location center, double radius, double damage, int particleCount, int extra) {
        World world = center.getWorld();
        if (world == null) return;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (entity.equals(owner) || !(entity instanceof LivingEntity target) || target.isDead()) continue;
            target.damage(damage, owner);
        }
        world.spawnParticle(Particle.CRIT, center, particleCount, radius / 2, 1, radius / 2, 0.25);
    }

    private void effectNearby(Player owner, Location center, double radius, PotionEffect effect) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.equals(owner) || !(entity instanceof LivingEntity target) || target.isDead()) continue;
            target.addPotionEffect(effect);
        }
    }

    private void igniteNearby(Player owner, Location center, double radius, int ticks) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.equals(owner) || !(entity instanceof LivingEntity target) || target.isDead()) continue;
            target.setFireTicks(Math.max(target.getFireTicks(), ticks));
        }
    }

    private void knockbackNearby(Player owner, Location center, double radius, double horizontal, double vertical, double damage) {
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.equals(owner) || !(entity instanceof LivingEntity target) || target.isDead()) continue;
            Vector vector = target.getLocation().toVector().subtract(center.toVector());
            if (vector.lengthSquared() < 0.001) vector = new Vector(0, 0, 1);
            target.setVelocity(vector.normalize().multiply(horizontal).setY(vertical));
            target.damage(damage, owner);
        }
    }

    private Location forwardLocation(Player player, double distance) {
        Location origin = player.getEyeLocation();
        Vector direction = origin.getDirection().normalize().multiply(distance);
        Location target = origin.clone().add(direction);
        target.setY(Math.max(0.0, target.getY()));
        return target;
    }

    private boolean safeForTeleport(Location location) {
        return location.getWorld() != null
                && location.getBlock().getType().isAir()
                && location.clone().add(0, 1, 0).getBlock().getType().isAir()
                && location.clone().subtract(0, 1, 0).getBlock().getType().isSolid();
    }

    private void particleSphere(Location center, Particle particle, double radius, int count) {
        if (center.getWorld() == null) return;
        center.getWorld().spawnParticle(particle, center, count, radius / 2, radius / 3, radius / 2, 0.08);
    }

    private void ringParticles(Location center, Particle particle, Object data, double radius) {
        if (center.getWorld() == null) return;
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2 * i / 36.0;
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            if (data == null) center.getWorld().spawnParticle(particle, center.clone().add(x, 0.1, z), 3, 0.05, 0.05, 0.05, 0.02);
            else if (data instanceof org.bukkit.block.data.BlockData blockData) center.getWorld().spawnParticle(particle, center.clone().add(x, 0.1, z), 3, 0.05, 0.05, 0.05, 0.02, blockData);
        }
    }

    private void applyMaxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        double desired = Math.max(2.0, getHearts(player) * 2.0);
        if (Math.abs(attribute.getBaseValue() - desired) > 0.001) attribute.setBaseValue(desired);
        if (player.getHealth() > desired) player.setHealth(desired);
    }

    private int getHearts(Player player) {
        return player.getPersistentDataContainer().getOrDefault(heartsKey, PersistentDataType.INTEGER, DEFAULT_START_HEARTS);
    }

    private void setHearts(Player player, int hearts) {
        int min = getConfig().getInt("min-hearts", DEFAULT_MIN_HEARTS);
        int max = getConfig().getInt("max-hearts", DEFAULT_MAX_HEARTS);
        int clamped = Math.max(min, Math.min(max, hearts));
        player.getPersistentDataContainer().set(heartsKey, PersistentDataType.INTEGER, clamped);
        applyMaxHealth(player);
    }

    private void removeStoneItems(PlayerInventory inventory) {
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isStone(item)) inventory.setItem(i, null);
        }
    }

    private void giveStone(Player player, StoneType type, boolean replaceExisting) {
        if (replaceExisting) removeStoneItems(player.getInventory());
        ItemStack item = createStone(type);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.getPersistentDataContainer().set(stoneKey, PersistentDataType.STRING, type.id);
        player.sendMessage(PREFIX + "You received " + type.displayName + "§f!");
    }

    private StoneType randomStone() {
        StoneType[] values = StoneType.values();
        return values[random.nextInt(values.length)];
    }

    private ItemStack createStone(StoneType type) {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(type.displayName);
        meta.setLore(type.lore());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(stoneKey, PersistentDataType.STRING, type.id);
        try {
            var component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float) type.modelData));
            meta.setCustomModelDataComponent(component);
        } catch (Throwable ignored) {
            meta.setCustomModelData(type.modelData);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isStone(ItemStack item) {
        return getStoneType(item) != null;
    }

    private StoneType getStoneType(ItemStack item) {
        if (item == null || item.getType() != Material.ECHO_SHARD || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(stoneKey, PersistentDataType.STRING);
        return StoneType.fromId(id);
    }

    private StoneType getOwnedStone(Player player) {
        return StoneType.fromId(player.getPersistentDataContainer().get(stoneKey, PersistentDataType.STRING));
    }

    private long cooldownSeconds(AbilitySlot slot) {
        return Math.max(1, getConfig().getLong(slot == AbilitySlot.FIRST ? "cooldowns.ability-1" : "cooldowns.ability-2", slot == AbilitySlot.FIRST ? 12L : 25L));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("stone")) return false;
        if (args.length == 0) {
            sender.sendMessage("§6§lStonePowers §7commands: /stone hearts, /stone sethearts, /stone resethearts, /stone give, /stone giveall, /stone reroll, /stone reload");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "hearts" -> {
                Player target;
                if (args.length >= 2) {
                    target = getServer().getPlayerExact(args[1]);
                    if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("§cConsole: use /stone hearts <player>.");
                    return true;
                }
                sender.sendMessage("§6" + target.getName() + "§f has §e" + getHearts(target) + "§f hearts.");
            }
            case "sethearts" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 3) { sender.sendMessage("§cUsage: /stone sethearts <player> <amount>"); return true; }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                Integer amount = parseInt(args[2]);
                if (amount == null) { sender.sendMessage("§cAmount must be a whole number."); return true; }
                setHearts(target, amount);
                sender.sendMessage("§aSet " + target.getName() + " to " + getHearts(target) + " hearts.");
            }
            case "resethearts" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) { sender.sendMessage("§cUsage: /stone resethearts <player>"); return true; }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                setHearts(target, getConfig().getInt("start-hearts", DEFAULT_START_HEARTS));
                sender.sendMessage("§aReset " + target.getName() + " to " + getHearts(target) + " hearts.");
            }
            case "give" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 3) { sender.sendMessage("§cUsage: /stone give <player> <stone>"); return true; }
                Player target = getServer().getPlayerExact(args[1]);
                StoneType stone = StoneType.fromId(args[2]);
                if (target == null || stone == null) { sender.sendMessage("§cInvalid player or stone."); return true; }
                giveStone(target, stone, true);
                sender.sendMessage("§aGave " + stone.displayName + " §ato " + target.getName() + ".");
            }
            case "giveall" -> {
                if (!requireAdmin(sender)) return true;
                Player target;
                if (args.length >= 2) target = getServer().getPlayerExact(args[1]);
                else target = sender instanceof Player p ? p : null;
                if (target == null) { sender.sendMessage("§cUsage: /stone giveall <player> (console) or run as a player."); return true; }
                removeStoneItems(target.getInventory());
                for (StoneType stone : StoneType.values()) {
                    Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createStone(stone));
                    leftovers.values().forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
                }
                sender.sendMessage("§aGave all 8 StonePowers stones to " + target.getName() + ".");
            }
            case "reroll" -> {
                if (!requireAdmin(sender)) return true;
                if (args.length < 2) { sender.sendMessage("§cUsage: /stone reroll <player>"); return true; }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                giveStone(target, randomStone(), true);
                sender.sendMessage("§aRerolled " + target.getName() + "'s stone.");
            }
            case "reload" -> {
                if (!requireAdmin(sender)) return true;
                reloadConfig();
                sender.sendMessage("§aStonePowers configuration reloaded.");
            }
            default -> sender.sendMessage("§cUnknown subcommand. Use /stone for help.");
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("stonepowers.admin")) return true;
        sender.sendMessage("§cYou do not have permission to use that command.");
        return false;
    }

    private Integer parseInt(String text) {
        try { return Integer.parseInt(text); } catch (NumberFormatException ex) { return null; }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("stone")) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.addAll(List.of("hearts", "sethearts", "resethearts", "give", "giveall", "reroll", "reload"));
        } else if (args.length == 2 && List.of("sethearts", "resethearts", "give", "giveall", "reroll").contains(args[0].toLowerCase(Locale.ROOT))) {
            result.addAll(getServer().getOnlinePlayers().stream().map(Player::getName).toList());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            result.addAll(List.of("fire", "ice", "ender", "lightning", "water", "earth", "wind", "wither"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return result.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    private enum AbilitySlot { FIRST, SECOND }

    private enum StoneType {
        FIRE("fire", "§6§lFIRE STONE", 1,
                "§aPASSIVE ABILITIES", "§7• Fire Resistance", "§7• Fire Power — Strength I + burning melee", "§cACTIVE ABILITIES", "§7• Flame Burst — Right Click", "§7• Meteor Strike — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        ICE("ice", "§b§lICE STONE", 2,
                "§aPASSIVE ABILITIES", "§7• Freeze Resistance — reduced freezing", "§7• Frozen Power — Resistance I", "§cACTIVE ABILITIES", "§7• Frost Wave — Right Click", "§7• Ice Prison — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        ENDER("ender", "§5§lENDER STONE", 3,
                "§aPASSIVE ABILITIES", "§7• Ender Vision — Night Vision", "§7• Ender Mobility — Speed I + Slow Falling", "§cACTIVE ABILITIES", "§7• Ender Dash — Right Click", "§7• Void Pull — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        LIGHTNING("lightning", "§e§lLIGHTNING STONE", 4,
                "§aPASSIVE ABILITIES", "§7• Lightning Resistance", "§7• Storm Speed — Speed II", "§cACTIVE ABILITIES", "§7• Thunder Strike — Right Click", "§7• Storm Field — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        WATER("water", "§3§lWATER STONE", 5,
                "§aPASSIVE ABILITIES", "§7• Water Breathing", "§7• Ocean Speed — Dolphin's Grace", "§cACTIVE ABILITIES", "§7• Water Blast — Right Click", "§7• Tsunami — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        EARTH("earth", "§6§lEARTH STONE", 6,
                "§aPASSIVE ABILITIES", "§7• Earth Resistance — Resistance II", "§7• Earth Armor — Absorption", "§cACTIVE ABILITIES", "§7• Earthquake — Right Click", "§7• Earth Shield — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        WIND("wind", "§f§lWIND STONE", 7,
                "§aPASSIVE ABILITIES", "§7• Wind Speed — Speed II", "§7• Wind Jump — Jump Boost II + Slow Falling", "§cACTIVE ABILITIES", "§7• Wind Dash — Right Click", "§7• Tornado — Sneak + Right Click", "§8Cooldown: 12s / 25s"),
        WITHER("wither", "§8§lWITHER STONE", 8,
                "§aPASSIVE ABILITIES", "§7• Wither Resistance", "§7• Dark Regeneration — Regeneration I + Resistance I", "§cACTIVE ABILITIES", "§7• Wither Blast — Right Click", "§7• Wither Storm — Sneak + Right Click", "§8Cooldown: 12s / 25s");

        private final String id;
        private final String displayName;
        private final int modelData;
        private final String[] loreLines;

        StoneType(String id, String displayName, int modelData, String... loreLines) {
            this.id = id;
            this.displayName = displayName;
            this.modelData = modelData;
            this.loreLines = loreLines;
        }

        List<String> lore() { return List.of(loreLines); }

        static StoneType fromId(String id) {
            if (id == null) return null;
            for (StoneType value : values()) if (value.id.equalsIgnoreCase(id)) return value;
            return null;
        }
    }
}
