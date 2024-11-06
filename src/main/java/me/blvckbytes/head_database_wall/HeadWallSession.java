package me.blvckbytes.head_database_wall;

import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import me.arcaniax.hdb.object.head.Head;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public class HeadWallSession {

  @FunctionalInterface
  private interface HeadLocationConsumer {
    void handle(int slotIndex, LocationAndHash locationAndHash);
  }

  private record LocationAndHash(Location location, long hash) {
    LocationAndHash(Location location) {
      this(location, fastCoordinateHash(location));
    }
  }

  private final LocationAndHash[][] headGrid;
  private final LocationAndHash[] viewingBoxLocations;
  private final LocationAndHash[] wallLocations;
  private final BlockFace lookingFace;
  private final Long2ObjectMap<Head> headByLocationHash;
  private final Long2ObjectMap<Runnable> restoreRoutineByLocationHash;

  public final Player viewer;
  private final Location bottomCenter;

  private final HeadWallParameters parameters;
  private final List<Head> heads;

  private boolean didInitializeAuxiliaryLocations;
  private boolean didDrawHeads;

  private final int pageSize;
  private final int numberOfPages;
  private int currentPage;

  private final HeadWallCommunicator communicator;

  public HeadWallSession(
    Player viewer,
    List<Head> heads,
    HeadWallParameters parameters,
    HeadWallCommunicator communicator
  ) {
    this.viewer = viewer;
    this.parameters = parameters;
    this.heads = heads;
    this.communicator = communicator;

    this.headByLocationHash = new Long2ObjectAVLTreeMap<>();
    this.restoreRoutineByLocationHash = new Long2ObjectAVLTreeMap<>();

    this.pageSize = parameters.rows() * parameters.columns();
    this.numberOfPages = Math.max(1, (heads.size() + (pageSize - 1)) / pageSize);

    this.headGrid = new LocationAndHash[parameters.rows()][parameters.columns()];
    this.wallLocations = new LocationAndHash[pageSize];

    var wallDistance = parameters.distance() + 1;

    // Include the player's location itself, as to ensure a complete lack of obstructions
    var viewingBoxDepth = parameters.distance() + 1;

    this.viewingBoxLocations = new LocationAndHash[pageSize * viewingBoxDepth];

    var viewerLocation = viewer.getLocation();
    lookingFace = decideLookingFace(viewerLocation.getYaw());
    var lookingFaceOrthogonal = decideLeftOrthogonal(lookingFace);
    var halfWidth = parameters.columns() / 2;

    bottomCenter = viewerLocation.add(
      lookingFace.getModX() * wallDistance,
      0,
      lookingFace.getModZ() * wallDistance
    );

    var bottomLeft = bottomCenter.add(
      lookingFaceOrthogonal.getModX() * halfWidth,
      0,
      lookingFaceOrthogonal.getModZ() * halfWidth
    );

    var lookingFaceOrthogonalOpposite = lookingFaceOrthogonal.getOppositeFace();
    var lookingFaceOpposite = lookingFace.getOppositeFace();

    var viewingBoxLocationsIndex = 0;
    var wallLocationsIndex = 0;

    for (var rowIndex = 0; rowIndex < this.headGrid.length; ++rowIndex) {
      var headRow = this.headGrid[rowIndex];

      for (var columnIndex = 0; columnIndex < headRow.length; ++columnIndex) {
        var currentWallLocation = bottomLeft.clone().add(
          lookingFaceOrthogonalOpposite.getModX() * columnIndex,
          rowIndex,
          lookingFaceOrthogonalOpposite.getModZ() * columnIndex
        );

        var currentHeadLocation = currentWallLocation.clone().add(
          lookingFaceOpposite.getModX(),
          0,
          lookingFaceOpposite.getModZ()
        );

        wallLocations[wallLocationsIndex++] = new LocationAndHash(currentWallLocation);
        headRow[columnIndex] = new LocationAndHash(currentHeadLocation);

        // Offset in [1;depth] and add one to account for the heads themselves
        for (var depthOffset = 2; depthOffset <= viewingBoxDepth + 1; ++depthOffset) {
          viewingBoxLocations[viewingBoxLocationsIndex++] = new LocationAndHash(
            currentWallLocation.clone().add(
            lookingFaceOpposite.getModX() * depthOffset,
            0,
            lookingFaceOpposite.getModZ() * depthOffset
            )
          );
        }
      }
    }
  }

  private void captureRestoreRoutineAndExecute(LocationAndHash locationAndHash, Runnable routine) {
    restoreRoutineByLocationHash.put(locationAndHash.hash, routine);
    routine.run();
  }

  public int getNumberOfPages() {
    return numberOfPages;
  }

  public int getCurrentPage() {
    return currentPage + 1;
  }

  private BlockFace decideLeftOrthogonal(BlockFace face) {
    /*
          -N
           ^
           |    |
      W <--+--> E
      |    |
           v
           S-
     */

    return switch (face) {
      case NORTH -> BlockFace.WEST;
      case WEST -> BlockFace.SOUTH;
      case SOUTH -> BlockFace.EAST;
      case EAST -> BlockFace.NORTH;
      default -> throw new IllegalStateException("Unexpected face " + face);
    };
  }

  private BlockFace decideLookingFace(float yaw) {
    /*
      [45;135] -x, WEST
      [135;180] | [-180;-135] -z, NORTH
      [-135;-45] +x, EAST
      [-45;45] +z, SOUTH
     */

    if (yaw >= 45 && yaw <= 135)
      return BlockFace.WEST;

    if (yaw >= -135 && yaw <= -45)
      return BlockFace.EAST;

    if (yaw >= -45 && yaw <= 45)
      return BlockFace.SOUTH;

    return BlockFace.NORTH;
  }

  public void nextPage() {
    if (currentPage >= numberOfPages - 1)
      this.currentPage = 0;
    else
      ++currentPage;

    this.show();
  }

  public void previousPage() {
    if (currentPage == 0)
      this.currentPage = this.numberOfPages - 1;
    else
      --currentPage;

    this.show();
  }

  public double distanceSquaredTo(Location location) {
    if (!Objects.requireNonNull(this.bottomCenter.getWorld()).equals(location.getWorld()))
      return -1;

    return bottomCenter.distanceSquared(location);
  }

  public @Nullable Head getHeadAtLocation(Location target) {
    if (!Objects.requireNonNull(this.bottomCenter.getWorld()).equals(target.getWorld()))
      return null;

    return headByLocationHash.get(fastCoordinateHash(target));
  }

  public boolean areCoordinatesPartOfSession(int x, int y, int z) {
    return restoreRoutineByLocationHash.containsKey(fastCoordinateHash(x, y, z));
  }

  public void onTryBlockManipulate(Location location) {
    var restoreRoutine = restoreRoutineByLocationHash.get(fastCoordinateHash(location));

    if (restoreRoutine != null)
      restoreRoutine.run();

    // Restore the real block, as no event will be called (see reasoning for ack)
    else
      communicator.sendBlockChange(viewer, location, location.getBlock().getBlockData());
  }

  public void show() {
    if (!didInitializeAuxiliaryLocations) {
      didInitializeAuxiliaryLocations = true;

      var wallTypeBlockData = parameters.wallType().createBlockData();
      for (var wallLocationAndHash : wallLocations) {
        captureRestoreRoutineAndExecute(
          wallLocationAndHash,
          () -> communicator.sendBlockChange(viewer, wallLocationAndHash.location, wallTypeBlockData)
        );
      }

      var airBlockData = Material.AIR.createBlockData();
      for (var viewingBoxLocationAndHash : viewingBoxLocations) {
        captureRestoreRoutineAndExecute(
          viewingBoxLocationAndHash,
          () -> communicator.sendBlockChange(viewer, viewingBoxLocationAndHash.location, airBlockData)
        );
      }
    }

    didDrawHeads = true;

    forEachHeadLocationTopLeftToBottomRight((slotIndex, locationAndHash) -> {
      var headIndex = currentPage * pageSize + slotIndex;

      if (headIndex >= heads.size()) {
        communicator.sendBlockChange(viewer, locationAndHash.location, Material.AIR.createBlockData());
        headByLocationHash.remove(locationAndHash.hash);
        return;
      }

      var currentHead = heads.get(headIndex);

      headByLocationHash.put(locationAndHash.hash, currentHead);

      captureRestoreRoutineAndExecute(
        locationAndHash,
        () -> communicator.updateBlockToTexturedSkull(viewer, lookingFace.getOppositeFace(), locationAndHash.location, currentHead.b64)
      );
    });
  }

  public void close() {
    // TODO: Only sending block-data will result in a loss of block-state...

    if (didInitializeAuxiliaryLocations) {
      didInitializeAuxiliaryLocations = false;

      for (var wallLocationAndHash : wallLocations) {
        var location = wallLocationAndHash.location;
        communicator.sendBlockChange(viewer, location, location.getBlock().getBlockData());
      }

      for (var viewingBoxLocationAndHash : viewingBoxLocations) {
        var location = viewingBoxLocationAndHash.location;
        communicator.sendBlockChange(viewer, location, location.getBlock().getBlockData());
      }
    }

    if (didDrawHeads) {
      didDrawHeads = false;

      forEachHeadLocationTopLeftToBottomRight((slotIndex, locationAndHash) -> {
        var location = locationAndHash.location;
        communicator.sendBlockChange(viewer, location, location.getBlock().getBlockData());
      });
    }
  }

  private void forEachHeadLocationTopLeftToBottomRight(HeadLocationConsumer locationConsumer) {
    var slotIndex = 0;

    for (var rowIndex = this.headGrid.length - 1; rowIndex >= 0; --rowIndex) {
      var headRow = this.headGrid[rowIndex];

      for (LocationAndHash locationAndHash : headRow) {
        locationConsumer.handle(slotIndex++, locationAndHash);
      }
    }
  }

  private static long fastCoordinateHash(Location location) {
    return fastCoordinateHash(location.getBlockX(), location.getBlockY(), location.getBlockZ());
  }

  private static long fastCoordinateHash(int x, int y, int z) {
    // y in [-64;320] - adding 64 will result in [0;384], thus 9 bits will suffice
    // long has 64 bits, (64-9)/2 = 27.5, thus, let's reserve 10 bits for y, and add 128, for future-proofing
    // 27 bits per x/z axis, with one sign-bit, => +- 67,108,864
    // As far as I know, the world is limited to around +- 30,000,000 - so we're fine

    return (
      // 2^10 - 1 = 0x3FF
      // 2^26 - 1 = 0x3FFFFFF
      // 2^26     = 0x4000000
      ((y + 128) & 0x3FF) |
        (((x & 0x3FFFFFF) | (x < 0 ? 0x4000000L : 0)) << 10) |
        (((z & 0x3FFFFFF) | (z < 0 ? 0x4000000L : 0)) << (10 + 27))
    );
  }
}
