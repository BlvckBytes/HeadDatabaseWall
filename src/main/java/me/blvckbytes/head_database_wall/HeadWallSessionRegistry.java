package me.blvckbytes.head_database_wall;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.WrappedRegistrable;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import me.arcaniax.hdb.object.head.Head;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HeadWallSessionRegistry extends PacketAdapter implements Listener, HeadWallCommunicator {

  private static final long INTERACTION_MIN_DISTANCE_MS = 250; // Up to 4 heads/second will suffice, I believe, x)
  private static final double REMOVAL_DISTANCE_BLOCKS = 10;
  private static final double REMOVAL_DISTANCE_BLOCKS_SQUARED = REMOVAL_DISTANCE_BLOCKS * REMOVAL_DISTANCE_BLOCKS;

  private static final HeadWallParameters WALL_PARAMETER = new HeadWallParameters(5, 8, 3, Material.COAL_BLOCK);

  private final Map<UUID, HeadWallSession> sessionByPlayerId;

  private final ProtocolManager protocolManager;
  private final Logger logger;

  private long lastProcessedInteractionStamp;
  private final Field blockDataHandleField;

  public HeadWallSessionRegistry(Plugin plugin, ProtocolManager protocolManager, Logger logger) throws Exception {
    super(
      plugin, ListenerPriority.HIGHEST,
      PacketType.Play.Client.BLOCK_DIG,
      PacketType.Play.Client.USE_ITEM_ON,
      PacketType.Play.Client.USE_ITEM,

      // Blocking all packets this listener is filtering for when in a session, as to avoid
      // unintentional updates due to events on the server, caused externally.
      PacketType.Play.Server.BLOCK_CHANGE,
      PacketType.Play.Server.TILE_ENTITY_DATA
      // TODO: A MULTI_BLOCK_CHANGE could cause block-changes within a session, but that's a bit more
      //       involved to get right, as the packet would need to have it's contents patched
    );

    var blockDataClass = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".block.data.CraftBlockData");

    Field handleField = null;

    for (var field : blockDataClass.getDeclaredFields()) {
      if (!field.getType().getPackageName().startsWith("net.minecraft"))
        continue;

      if (handleField != null)
        throw new IllegalStateException("Found multiple candidates for the handle-field within " + blockDataClass);

      handleField = field;
    }

    if (handleField == null)
      throw new IllegalStateException("Could not locate the handle-field within " + blockDataClass);

    this.blockDataHandleField = handleField;
    this.blockDataHandleField.setAccessible(true);

    this.sessionByPlayerId = new HashMap<>();
    this.protocolManager = protocolManager;
    this.logger = logger;
  }

  @Override
  public void sendBlockChange(Player player, Location location, BlockData blockData) {
    try {
      var packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGE);

      packet.getBlockPositionModifier().write(0, new BlockPosition(
        location.getBlockX(), location.getBlockY(), location.getBlockZ()
      ));

      packet.getBlockData().write(0, convertBlockData(blockData));

      protocolManager.sendServerPacket(player, packet, false);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "An error occurred while trying to send a change regarding a fake-block", e);
    }
  }

  @Override
  public void updateBlockToTexturedSkull(Player player, BlockFace mountingFace, Location location, String base64Textures) {
    try {
      var headBlockData = Material.PLAYER_WALL_HEAD.createBlockData();
      ((Directional) headBlockData).setFacing(mountingFace);

      var packet = protocolManager.createPacket(PacketType.Play.Server.TILE_ENTITY_DATA);

      packet.getBlockPositionModifier().write(0, new BlockPosition(
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ()
      ));

      packet.getBlockEntityTypeModifier().write(0, WrappedRegistrable.blockEntityType("skull"));

      var rootCompound = NbtFactory.ofCompound("")
        .put(
          NbtFactory.ofCompound("profile")
            .put("name", "HeadDatabase")
            .put(NbtFactory.ofList(
              "properties",
              NbtFactory.ofCompound("")
                .put("name", "textures")
                .put("value", base64Textures)
            ))
        );

      packet.getNbtModifier().write(0, rootCompound);
      sendBlockChange(player, location, headBlockData);
      protocolManager.sendServerPacket(player, packet, false);
    } catch (Exception e) {
      logger.log(Level.SEVERE, "An error occurred while trying to update a fake-block to a textured skull", e);
    }
  }

  public WrappedBlockData convertBlockData(BlockData blockData) {
    try {
      return WrappedBlockData.fromHandle(blockDataHandleField.get(blockData));
    } catch (Exception e) {
      logger.log(Level.SEVERE, "An error occurred while trying to convert NMS block-data to a ProtocolLib-wrapped instance", e);
      // Rather fail safely, as these changes are only fake on the client-side anyway
      return WrappedBlockData.createData(Material.AIR);
    }
  }

  @Override
  public void onPacketSending(PacketEvent event) {
    try {
      tryAccessSession(event.getPlayer(), session -> {
        var position = event.getPacket().getBlockPositionModifier().read(0);

        if (session.areCoordinatesPartOfSession(position.getX(), position.getY(), position.getZ()))
          event.setCancelled(true);
      });
    } catch (Exception e) {
      logger.log(Level.SEVERE, "An error occurred while trying to handle a sent packet", e);
    }
  }

  @Override
  public void onPacketReceiving(PacketEvent event) {
    try {
      tryAccessSession(event.getPlayer(), session -> {
        var mainHandItem = session.viewer.getInventory().getItemInMainHand();

        boolean wasLeft = false;
        Location interactionLocation = null;

        // All listened-to packet-types contain ACK sequence-numbers
        if (PacketType.Play.Server.BLOCK_CHANGED_ACK.isSupported()) {
          int blockChangeAckId = event.getPacket().getIntegers().read(0);

          // Always acknowledge, as block-changes are not allowed to pass through to the server.
          // Without acknowledgement, the client will refuse to accept follow-up block-updates.
          var ackPacket = protocolManager.createPacket(PacketType.Play.Server.BLOCK_CHANGED_ACK);
          ackPacket.getIntegers().write(0, blockChangeAckId);
          protocolManager.sendServerPacket(session.viewer, ackPacket, false);
        }

        // Block break; left-click
        // Let's not go into as much detail as to figure out whether the block actually broke, just update it regardless.
        if (event.getPacket().getType() == PacketType.Play.Client.BLOCK_DIG) {
          var position = event.getPacket().getBlockPositionModifier().read(0);

          interactionLocation = new Location(
            session.viewer.getWorld(),
            position.getX(), position.getY(), position.getZ()
          );

          session.onTryBlockManipulate(interactionLocation);
          wasLeft = true;
        }

        // Block place or interaction; right-click
        else if (event.getPacket().getType() == PacketType.Play.Client.USE_ITEM_ON) {
          var movingPosition = event.getPacket().getMovingBlockPositions().read(0);
          var position = movingPosition.getBlockPosition();

          interactionLocation = new Location(
            session.viewer.getWorld(),
            position.getX(),
            position.getY(),
            position.getZ()
          );

          var mainHandItemType = mainHandItem.getType();

          var doesBuild = !mainHandItemType.isAir() && (
            mainHandItemType.isBlock() ||
              mainHandItemType == Material.WATER_BUCKET ||
              mainHandItemType == Material.LAVA_BUCKET
          );

          if (doesBuild) {
            var blockFace = protocolLibDirectionToBlockPosition(movingPosition.getDirection());

            session.onTryBlockManipulate(
              interactionLocation.clone().add(
                blockFace.getModX(),
                blockFace.getModY(),
                blockFace.getModZ()
              )
            );
          } else
            session.onTryBlockManipulate(interactionLocation);
        }

        // else: USE_ITEM -> Interaction into air with item in hand, just cancel

        event.setCancelled(true);

        // Force inventory-update, as to re-set the stack-size and durability
        session.viewer.getInventory().setItemInMainHand(mainHandItem);

        if (interactionLocation == null)
          return;

        // Since this is called based on received packets, and interactions may fire multiple times
        // within a short time-span, debounce relaying this call to the underlying implementation.

        if (System.currentTimeMillis() - lastProcessedInteractionStamp < INTERACTION_MIN_DISTANCE_MS)
          return;

        onSessionInteract(session, interactionLocation, wasLeft);
        lastProcessedInteractionStamp = System.currentTimeMillis();
      });
    } catch (Exception e) {
      logger.log(Level.SEVERE, "An error occurred while trying to handle a received packet", e);
    }
  }

  private BlockFace protocolLibDirectionToBlockPosition(EnumWrappers.Direction direction) {
    return switch (direction) {
      case DOWN -> BlockFace.DOWN;
      case UP -> BlockFace.UP;
      case NORTH -> BlockFace.NORTH;
      case SOUTH -> BlockFace.SOUTH;
      case WEST -> BlockFace.WEST;
      case EAST -> BlockFace.EAST;
    };
  }

  public void checkSessionsForDistanceRemoval() {
    for (var sessionIterator = sessionByPlayerId.values().iterator(); sessionIterator.hasNext();) {
      var session = sessionIterator.next();
      var sessionDistanceSquared = session.distanceSquaredTo(session.viewer.getLocation());

      if (sessionDistanceSquared <= REMOVAL_DISTANCE_BLOCKS_SQUARED)
        continue;

      session.viewer.sendMessage("§cYou've exceeded the max distance of " + REMOVAL_DISTANCE_BLOCKS + "; exited session.");
      session.close();
      sessionIterator.remove();
    }
  }

  public @Nullable HeadWallSession createAndRegister(Player player, List<Head> heads) {
    var playerId = player.getUniqueId();

    if (sessionByPlayerId.containsKey(playerId))
      return null;

    var session = new HeadWallSession(player, heads, WALL_PARAMETER, this);

    this.sessionByPlayerId.put(playerId, session);
    return session;
  }

  private void onSessionInteract(HeadWallSession session, Location location, boolean wasLeft) {
    var correspondingHead = session.getHeadAtLocation(location);

    if (correspondingHead == null) {
      session.viewer.sendMessage("§cPlease click directly on a head; left-click to request, right-click for information, sneak to exit");
      return;
    }

    if (wasLeft) {
      session.viewer.getInventory().addItem(correspondingHead.getHead());
      session.viewer.sendMessage("§aYou've been given the head " + correspondingHead.name);
      return;
    }

    session.viewer.sendMessage("§8§m                              ");
    session.viewer.sendMessage("§aName: " + correspondingHead.name);
    session.viewer.sendMessage("§aCategory: " + correspondingHead.c.name());
    session.viewer.sendMessage("§aTags: " + String.join(", ", correspondingHead.tags));
    session.viewer.sendMessage("§8§m                              ");
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    sessionByPlayerId.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler
  public void onSneak(PlayerToggleSneakEvent event) {
    if (!event.isSneaking())
      return;

    tryAccessSession(event.getPlayer(), session -> {
      session.viewer.sendMessage("§aEnding the head-wall session.");
      session.close();
      sessionByPlayerId.remove(session.viewer.getUniqueId());
    });
  }

  @EventHandler
  public void onScroll(PlayerItemHeldEvent event) {
    tryAccessSession(event.getPlayer(), session -> {
      var isForwards = event.getPreviousSlot() < event.getNewSlot();

      if (isForwards) {
        session.nextPage();
        session.viewer.sendMessage("§aNavigated to the next page, at " + session.getCurrentPage() + "/" + session.getNumberOfPages());
        return;
      }

      session.previousPage();
      session.viewer.sendMessage("§aNavigated to the previous page, at " + session.getCurrentPage() + "/" + session.getNumberOfPages());
    });
  }

  private void tryAccessSession(@Nullable Player player, Consumer<HeadWallSession> handler) {
    if (player == null)
      return;

    var session = sessionByPlayerId.get(player.getUniqueId());

    if (session != null)
      handler.accept(session);
  }

  public void onShutdown() {
    for (var sessionIterator = sessionByPlayerId.values().iterator(); sessionIterator.hasNext();) {
      sessionIterator.next().close();
      sessionIterator.remove();
    }
  }
}
