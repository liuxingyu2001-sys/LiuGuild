package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工会战宠物屏蔽（mcPets / LiuPets 与 MyPet，软依赖、反射调用）。
 *
 * <p>参赛者、旁观者及赛季战斗玩家在战斗期间不能召唤、骑乘宠物或触发宠物技能；
 * 已存在的宠物会被移除。插件缺失、版本不匹配或功能关闭时自动跳过。</p>
 */
public final class WarPetHook implements Listener {

    private final LiuGuild plugin;

    private boolean mcPets;
    private boolean myPet;

    private Class<? extends Event> mcPetSpawnEvent;
    private Class<? extends Event> mcPetSpawnedEvent;
    private Class<? extends Event> mcPetSkillEvent;
    private Class<? extends Event> mcPetInteractEvent;
    private Class<? extends Event> mcPetMountEvent;
    private Method mcActivePets;
    private Method mcDespawn;
    private Object mcDespawnReason;

    private Class<? extends Event> myPetCallEvent;
    private Class<? extends Event> myPetActivatedEvent;
    private Class<? extends Event> myPetJoinEvent;
    private Method myPetManager;
    private Method myPetGet;
    private Method myPetRemove;

    public WarPetHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 初始化并登记事件；两个宠物插件均缺失或功能关闭时静默跳过。 */
    public void register() {
        if (!plugin.settings().main.petsBlockInWar) {
            return;
        }
        registerMcPets();
        registerMyPet();
        if (mcPets || myPet) {
            plugin.getLogger().info("工会战宠物屏蔽已启用：mcPets=" + mcPets + "，MyPet=" + myPet);
        }
    }

    /** 至少一个宠物插件可用。 */
    public boolean available() {
        return mcPets || myPet;
    }

    public boolean mcPetsAvailable() {
        return mcPets;
    }

    public boolean myPetAvailable() {
        return myPet;
    }

    /** 每秒由工会战 tick 调用，清掉战斗中仍存活的宠物，防止插件绕过召唤事件。 */
    public void enforceWarPlayers() {
        if (!available() || plugin.war() == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            enforce(player);
        }
    }

    /** 玩家入场时立即移除宠物，避免等待下一次 tick。 */
    public void enforce(Player player) {
        if (player == null || !player.isOnline() || !restricted(player.getUniqueId())) {
            return;
        }
        despawnMcPets(player.getUniqueId());
        removeMyPet(player);
    }

    private void registerMcPets() {
        Plugin dependency = findPlugin("LiuPets", "MCPets");
        if (dependency == null || !dependency.isEnabled()) {
            return;
        }
        ClassLoader loader = dependency.getClass().getClassLoader();
        mcPetSpawnEvent = eventClass(loader, "fr.nocsy.mcpets.events.PetSpawnEvent");
        mcPetSpawnedEvent = eventClass(loader, "fr.nocsy.mcpets.events.PetSpawnedEvent");
        mcPetSkillEvent = eventClass(loader, "fr.nocsy.mcpets.events.PetCastSkillEvent");
        mcPetInteractEvent = eventClass(loader, "fr.nocsy.mcpets.events.PetOwnerInteractEvent");
        mcPetMountEvent = eventClass(loader, "fr.nocsy.mcpets.events.EntityMountPetEvent");
        try {
            Class<?> petClass = Class.forName("fr.nocsy.mcpets.data.Pet", false, loader);
            mcActivePets = petClass.getMethod("getActivePetsForOwner", UUID.class);
            Class<?> reasonClass = Class.forName("fr.nocsy.mcpets.data.PetDespawnReason", false, loader);
            for (Object constant : reasonClass.getEnumConstants()) {
                if (constant instanceof Enum<?> value && value.name().equals("CANCELLED")) {
                    mcDespawnReason = constant;
                    break;
                }
            }
            if (mcDespawnReason != null) {
                mcDespawn = petClass.getMethod("despawn", reasonClass);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            mcActivePets = null;
            mcDespawn = null;
            mcDespawnReason = null;
        }
        mcPets = mcPetSpawnEvent != null || mcActivePets != null;
        if (mcPetSpawnEvent != null) {
            registerEvent(mcPetSpawnEvent, this::suppressMcPetSpawn);
        }
        if (mcPetSpawnedEvent != null) {
            registerEvent(mcPetSpawnedEvent, this::removeMcPetAfterSpawn);
        }
        if (mcPetSkillEvent != null) {
            registerEvent(mcPetSkillEvent, this::suppressMcPetEvent);
        }
        if (mcPetInteractEvent != null) {
            registerEvent(mcPetInteractEvent, this::suppressMcPetEvent);
        }
        if (mcPetMountEvent != null) {
            registerEvent(mcPetMountEvent, this::suppressMcPetEvent);
        }
    }

    private void registerMyPet() {
        Plugin dependency = findPlugin("MyPet");
        if (dependency == null || !dependency.isEnabled()) {
            return;
        }
        ClassLoader loader = dependency.getClass().getClassLoader();
        myPetCallEvent = eventClass(loader, "de.Keyle.MyPet.api.event.MyPetCallEvent");
        myPetActivatedEvent = eventClass(loader, "de.Keyle.MyPet.api.event.MyPetActivatedEvent");
        myPetJoinEvent = eventClass(loader, "de.Keyle.MyPet.api.event.MyPetPlayerJoinEvent");
        try {
            Class<?> api = Class.forName("de.Keyle.MyPet.MyPetApi", false, loader);
            myPetManager = api.getMethod("getMyPetManager");
            Class<?> manager = Class.forName("de.Keyle.MyPet.api.repository.MyPetManager", false, loader);
            myPetGet = manager.getMethod("getMyPet", Player.class);
            Class<?> pet = Class.forName("de.Keyle.MyPet.api.entity.MyPet", false, loader);
            myPetRemove = pet.getMethod("removePet");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            myPetManager = null;
            myPetGet = null;
            myPetRemove = null;
        }
        myPet = myPetCallEvent != null || myPetManager != null;
        if (myPetCallEvent != null) {
            registerEvent(myPetCallEvent, this::suppressMyPetCall);
        }
        if (myPetActivatedEvent != null) {
            registerEvent(myPetActivatedEvent, this::removeMyPetAfterActivate);
        }
        if (myPetJoinEvent != null) {
            registerEvent(myPetJoinEvent, this::removeMyPetAfterJoin);
        }
    }

    private void suppressMcPetSpawn(Event event) {
        Object pet = petOf(event);
        if (pet != null && restricted(petOwner(pet))) {
            cancel(event);
        }
    }

    private void removeMcPetAfterSpawn(Event event) {
        Object pet = petOf(event);
        if (pet != null && restricted(petOwner(pet))) {
            plugin.syncDelayed(() -> {
                if (restricted(petOwner(pet))) {
                    despawnMcPet(pet);
                }
            }, 1L);
        }
    }

    private void suppressMcPetEvent(Event event) {
        Object pet = petOf(event);
        if (pet != null && restricted(petOwner(pet))) {
            cancel(event);
        }
    }

    private void suppressMyPetCall(Event event) {
        Player player = playerOf(event);
        if (player != null && restricted(player.getUniqueId())) {
            cancel(event);
        }
    }

    private void removeMyPetAfterActivate(Event event) {
        Player player = playerOf(event);
        if (player != null && restricted(player.getUniqueId())) {
            plugin.syncDelayed(() -> {
                if (restricted(player.getUniqueId())) {
                    removeMyPet(player);
                }
            }, 1L);
        }
    }

    private void removeMyPetAfterJoin(Event event) {
        Player player = playerOf(event);
        if (player != null && restricted(player.getUniqueId())) {
            plugin.syncDelayed(() -> {
                if (restricted(player.getUniqueId())) {
                    removeMyPet(player);
                }
            }, 1L);
        }
    }

    private boolean restricted(UUID uuid) {
        if (uuid == null || plugin.war() == null) {
            return false;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.hasPermission("liuguild.admin")) {
            return false;
        }
        return plugin.war().isWarRestricted(uuid);
    }

    private void despawnMcPets(UUID owner) {
        if (!mcPets || owner == null || mcActivePets == null || mcDespawn == null || mcDespawnReason == null) {
            return;
        }
        try {
            Object value = mcActivePets.invoke(null, owner);
            if (!(value instanceof List<?> list)) {
                return;
            }
            for (Object pet : new ArrayList<>(list)) {
                if (pet != null && owner.equals(petOwner(pet))) {
                    despawnMcPet(pet);
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private void despawnMcPet(Object pet) {
        if (pet == null || mcDespawn == null || mcDespawnReason == null) {
            return;
        }
        try {
            mcDespawn.invoke(pet, mcDespawnReason);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private void removeMyPet(Player player) {
        if (!myPet || player == null || !player.isOnline()
                || !restricted(player.getUniqueId())
                || myPetManager == null || myPetGet == null || myPetRemove == null) {
            return;
        }
        try {
            Object manager = myPetManager.invoke(null);
            if (manager == null) {
                return;
            }
            Object pet = myPetGet.invoke(manager, player);
            if (pet != null) {
                myPetRemove.invoke(pet);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private Object petOf(Event event) {
        try {
            return event.getClass().getMethod("getPet").invoke(event);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private UUID petOwner(Object pet) {
        if (pet == null) {
            return null;
        }
        try {
            Object value = pet.getClass().getMethod("getOwner").invoke(pet);
            return value instanceof UUID uuid ? uuid : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private Player playerOf(Event event) {
        try {
            Object value = event.getClass().getMethod("getPlayer").invoke(event);
            if (value instanceof Player player) {
                return player;
            }
            // MyPetPlayerJoinEvent#getPlayer 返回 MyPetPlayer，需要再取一次 Bukkit Player。
            if (value != null) {
                Object nested = value.getClass().getMethod("getPlayer").invoke(value);
                if (nested instanceof Player player) {
                    return player;
                }
            }
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private void cancel(Event event) {
        try {
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
    }

    private void registerEvent(Class<? extends Event> type, java.util.function.Consumer<Event> handler) {
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST,
                (listener, event) -> handler.accept(event), plugin, true);
    }

    private Plugin findPlugin(String... names) {
        for (String name : names) {
            Plugin dependency = Bukkit.getPluginManager().getPlugin(name);
            if (dependency != null) {
                return dependency;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Event> eventClass(ClassLoader loader, String name) {
        try {
            Class<?> type = Class.forName(name, false, loader);
            if (Event.class.isAssignableFrom(type)) {
                return (Class<? extends Event>) type;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        return null;
    }
}
