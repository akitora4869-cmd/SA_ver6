package jp.skillarena.boss;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.*;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.comphenix.protocol.wrappers.Pair;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Client-only player entity rendered at the location of an authoritative, hidden zombie. */
public final class PacketVargasVisual {
  private static final AtomicInteger IDS = new AtomicInteger(1800000000);
  private final JavaPlugin plugin;
  private final ProtocolManager protocol;
  private final Zombie body;
  private final UUID uuid = UUID.randomUUID();
  private final int entityId = IDS.incrementAndGet();
  private final Set<UUID> viewers = new HashSet<>();
  private final Map<UUID,Long> meleeCooldown = new ConcurrentHashMap<>();
  private final WrappedGameProfile profile;
  private BukkitTask syncTask;
  private boolean closed;
  private final PacketAdapter attackListener;

  public PacketVargasVisual(JavaPlugin plugin, Zombie body) {
    this.plugin = plugin;
    this.body = body;
    this.protocol = ProtocolLibrary.getProtocolManager();
    profile = new WrappedGameProfile(uuid, "VargasBoss");
    String texture = plugin.getConfig().getString("vargas.skin.texture", "");
    String signature = plugin.getConfig().getString("vargas.skin.signature", "");
    if (!texture.isBlank() && !signature.isBlank()) {
      profile.getProperties().put("textures", new WrappedSignedProperty("textures", texture, signature));
    }
    attackListener = new PacketAdapter(plugin, PacketType.Play.Client.USE_ENTITY) {
      @Override public void onPacketReceiving(PacketEvent event) {
        if (closed || event.getPacket().getIntegers().read(0) != entityId) return;
        // Client interaction packets are not backed by a Bukkit entity. Cancel and process on main thread.
        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
          if (closed || !body.isValid() || !player.isOnline() || player.getWorld() != body.getWorld()) return;
          if (player.getLocation().distanceSquared(body.getLocation()) > 16) return;
          long now = System.currentTimeMillis();
          if (now < meleeCooldown.getOrDefault(player.getUniqueId(), 0L)) return;
          meleeCooldown.put(player.getUniqueId(), now + 500);
          // All attacks go through the actual boss entity, preserving damage events and HP tracking.
          body.damage(4.0, player);
        });
      }
    };
    protocol.addPacketListener(attackListener);
    for (Player player : Bukkit.getOnlinePlayers()) show(player);
    syncTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
      if (!body.isValid()) { close(); return; }
      for (Player player : Bukkit.getOnlinePlayers()) {
        if (player.getWorld() == body.getWorld()) {
          if (!viewers.contains(player.getUniqueId())) show(player);
          else teleport(player);
        } else if (viewers.contains(player.getUniqueId())) hide(player);
      }
    }, 1L, 2L);
  }

  public int entityId() { return entityId; }

  public void show(Player player) {
    if (closed || player.getWorld() != body.getWorld() || !viewers.add(player.getUniqueId())) return;
    player.hideEntity(plugin, body);
    try {
      PacketContainer info = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO);
      info.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER));
      info.getPlayerInfoDataLists().write(0, Collections.singletonList(
          new PlayerInfoData(profile, 0, EnumWrappers.NativeGameMode.SURVIVAL,
              WrappedChatComponent.fromText("§4剣帝ヴァルガス"))));
      send(player, info);
      PacketContainer spawn = protocol.createPacket(PacketType.Play.Server.NAMED_ENTITY_SPAWN);
      Location loc = body.getLocation();
      spawn.getIntegers().write(0, entityId);
      spawn.getUUIDs().write(0, uuid);
      spawn.getDoubles().write(0, loc.getX()).write(1, loc.getY()).write(2, loc.getZ());
      spawn.getBytes().write(0, angle(loc.getYaw())).write(1, angle(loc.getPitch()));
      send(player, spawn);
      PacketContainer equipment = protocol.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
      equipment.getIntegers().write(0, entityId);
      equipment.getSlotStackPairLists().write(0, Arrays.asList(
          new Pair<>(EnumWrappers.ItemSlot.MAINHAND, new ItemStack(Material.NETHERITE_SWORD)),
          new Pair<>(EnumWrappers.ItemSlot.HEAD, new ItemStack(Material.NETHERITE_HELMET)),
          new Pair<>(EnumWrappers.ItemSlot.CHEST, new ItemStack(Material.NETHERITE_CHESTPLATE)),
          new Pair<>(EnumWrappers.ItemSlot.LEGS, new ItemStack(Material.NETHERITE_LEGGINGS)),
          new Pair<>(EnumWrappers.ItemSlot.FEET, new ItemStack(Material.NETHERITE_BOOTS))));
      send(player, equipment);
    } catch (RuntimeException ex) {
      plugin.getLogger().warning("Vargas NPC packet spawn failed: " + ex.getMessage());
      hide(player);
    }
  }

  private void teleport(Player player) {
    try {
      Location loc = body.getLocation();
      PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
      packet.getIntegers().write(0, entityId);
      packet.getDoubles().write(0, loc.getX()).write(1, loc.getY()).write(2, loc.getZ());
      packet.getBytes().write(0, angle(loc.getYaw())).write(1, angle(loc.getPitch()));
      packet.getBooleans().write(0, body.isOnGround());
      send(player, packet);
      PacketContainer head = protocol.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
      head.getIntegers().write(0, entityId);
      head.getBytes().write(0, angle(loc.getYaw()));
      send(player, head);
    } catch (RuntimeException ex) {
      plugin.getLogger().fine("Vargas NPC sync failed: " + ex.getMessage());
    }
  }

  public void swing() {
    if (closed) return;
    PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ANIMATION);
    packet.getIntegers().write(0, entityId).write(1, 0);
    for (Player player : Bukkit.getOnlinePlayers()) if (viewers.contains(player.getUniqueId())) send(player, packet);
  }

  public void hide(Player player) {
    if (!viewers.remove(player.getUniqueId())) return;
    PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
    destroy.getIntLists().write(0, Collections.singletonList(entityId));
    send(player, destroy);
    PacketContainer remove = protocol.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
    remove.getUUIDLists().write(0, Collections.singletonList(uuid));
    send(player, remove);
    if (body.isValid()) player.showEntity(plugin, body);
  }

  public void close() {
    if (closed) return;
    closed = true;
    if (syncTask != null) syncTask.cancel();
    protocol.removePacketListener(attackListener);
    for (Player player : Bukkit.getOnlinePlayers()) hide(player);
    viewers.clear();
  }

  private static byte angle(float degrees) { return (byte) Math.floor(degrees * 256.0 / 360.0); }
  private void send(Player player, PacketContainer packet) {
    try { protocol.sendServerPacket(player, packet); }
    catch (RuntimeException ex) { plugin.getLogger().warning("Vargas packet send failed: " + ex.getMessage()); }
  }
}
