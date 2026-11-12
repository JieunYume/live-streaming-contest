package com.livecraft.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import com.google.gson.JsonObject;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

/**
 * 후원 반응 실행. 같은 actionId 는 한 번만 실행한다 (실행 후 ACK 가 유실돼 서버가 다시 보내도 안전).
 * 실행한 actionId 는 파일에 남겨서 서버를 재시작해도 기억한다.
 */
final class ActionExecutor {

    interface AckSender {
        void ack(long actionId, boolean success, String message);
    }

    private static final int REMEMBER = 2000;
    private static final List<Material> RANDOM_BOX = List.of(
            Material.DIAMOND, Material.GOLDEN_APPLE, Material.ENDER_PEARL, Material.IRON_INGOT, Material.EMERALD,
            Material.EXPERIENCE_BOTTLE, Material.TNT, Material.COOKIE, Material.ENCHANTED_GOLDEN_APPLE, Material.DIRT);

    private final BlockcastPlugin plugin;
    private final Path executedFile;
    private final Set<Long> executed = new LinkedHashSet<>();
    private final Deque<Long> order = new ArrayDeque<>();

    ActionExecutor(BlockcastPlugin plugin) {
        this.plugin = plugin;
        this.executedFile = plugin.getDataFolder().toPath().resolve("executed-actions.txt");
        try {
            if (Files.exists(executedFile)) {
                for (String line : Files.readAllLines(executedFile)) {
                    if (!line.isBlank()) remember(Long.parseLong(line.trim()));
                }
            }
        } catch (IOException | NumberFormatException e) {
            plugin.getLogger().warning("실행 기록을 읽지 못했어요: " + e.getMessage());
        }
    }

    /** 웹소켓 스레드에서 호출 → 게임 조작은 메인 스레드에서 */
    void execute(JsonObject msg, AckSender ack) {
        long actionId = msg.get("actionId").getAsLong();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (executed.contains(actionId)) {
                ack.ack(actionId, true, "이미 실행됨");
                return;
            }
            String playerName = msg.get("player").getAsString();
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) {
                ack.ack(actionId, false, playerName + " 님이 접속해 있지 않아요");
                return;
            }
            try {
                String result = run(player, msg);
                remember(actionId);
                save();
                announce(player, msg);
                ack.ack(actionId, true, result);
            } catch (RuntimeException e) {
                ack.ack(actionId, false, "실행 실패: " + e.getMessage());
            }
        });
    }

    private String run(Player player, JsonObject msg) {
        String type = msg.get("actionType").getAsString();
        String param = msg.has("param") && !msg.get("param").isJsonNull() ? msg.get("param").getAsString().trim() : null;
        Location loc = player.getLocation();
        return switch (type) {
            case "FOOD" -> {
                Material food = material(param, Material.COOKED_BEEF);
                give(player, new ItemStack(food, 16));
                yield food.name().toLowerCase(Locale.ROOT) + " 16개 지급";
            }
            case "RANDOM_BOX" -> {
                Material m = RANDOM_BOX.get(ThreadLocalRandom.current().nextInt(RANDOM_BOX.size()));
                int amount = m == Material.ENCHANTED_GOLDEN_APPLE ? 1 : 1 + ThreadLocalRandom.current().nextInt(8);
                give(player, new ItemStack(m, amount));
                yield "랜덤박스: " + m.name().toLowerCase(Locale.ROOT) + " " + amount + "개";
            }
            case "KILL" -> {
                player.setHealth(0);
                yield "즉사";
            }
            case "SPAWN_MOB" -> {
                EntityType mob = entity(param, EntityType.ZOMBIE);
                loc.getWorld().spawnEntity(loc.clone().add(2, 0, 0), mob);
                yield mob.name().toLowerCase(Locale.ROOT) + " 소환";
            }
            case "EFFECT" -> {
                PotionEffectType effect = effect(param);
                player.addPotionEffect(new PotionEffect(effect, 20 * 30, 1));
                yield effect.getKey().getKey() + " 30초";
            }
            case "COMMAND" -> {
                if (param == null) throw new IllegalArgumentException("명령어가 비어 있어요");
                String cmd = param.replace("{player}", player.getName());
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                if (!ok) throw new IllegalArgumentException("명령어 실행 실패: " + cmd);
                yield "명령어: " + cmd;
            }
            default -> throw new IllegalArgumentException("알 수 없는 반응 " + type);
        };
    }

    private void announce(Player player, JsonObject msg) {
        String donor = msg.get("donor").getAsString();
        long amount = msg.get("amount").getAsLong();
        String label = msg.get("label").getAsString();
        player.showTitle(Title.title(
                Component.text(label, NamedTextColor.GOLD),
                Component.text(donor + "님 " + String.format("%,d", amount) + "원 후원!", NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(500))));
        Bukkit.broadcast(Component.text("[BLOCKCAST] " + donor + "님이 " + player.getName() + "에게 " + label + "!", NamedTextColor.GREEN));
    }

    private static void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left)); // 인벤토리가 꽉 차면 바닥에
    }

    private static Material material(String id, Material fallback) {
        if (id == null || id.isEmpty()) return fallback;
        Material m = Material.matchMaterial(id);
        if (m == null || !m.isItem()) throw new IllegalArgumentException("아이템 id 가 올바르지 않아요: " + id);
        return m;
    }

    private static EntityType entity(String id, EntityType fallback) {
        if (id == null || id.isEmpty()) return fallback;
        EntityType t = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(id.toLowerCase(Locale.ROOT)));
        if (t == null || !t.isSpawnable() || !t.isAlive()) throw new IllegalArgumentException("몹 id 가 올바르지 않아요: " + id);
        return t;
    }

    private static PotionEffectType effect(String id) {
        String key = id == null || id.isEmpty() ? "speed" : id.toLowerCase(Locale.ROOT);
        PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(key));
        if (t == null) throw new IllegalArgumentException("효과 id 가 올바르지 않아요: " + id);
        return t;
    }

    private void remember(long actionId) {
        if (executed.add(actionId)) {
            order.addLast(actionId);
            while (order.size() > REMEMBER) executed.remove(order.removeFirst());
        }
    }

    private void save() {
        try {
            Files.createDirectories(executedFile.getParent());
            Files.write(executedFile, order.stream().map(String::valueOf).toList());
        } catch (IOException e) {
            plugin.getLogger().warning("실행 기록 저장 실패: " + e.getMessage());
        }
    }
}
