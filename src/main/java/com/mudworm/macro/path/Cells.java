package com.mudworm.macro.path;

/** Read access to block classes ({@link Cell} flags). Not thread-safe unless the implementation says so. */
public interface Cells {
    /** Flags of the block at x/y/z, or 0 when it is not known. */
    short at(int x, int y, int z);
}
