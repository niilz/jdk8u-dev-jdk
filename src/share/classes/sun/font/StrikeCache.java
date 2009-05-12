/*
 * Copyright 2003-2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package sun.font;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import sun.java2d.Disposer;
import sun.java2d.pipe.BufferedContext;
import sun.java2d.pipe.RenderQueue;
import sun.java2d.pipe.hw.AccelGraphicsConfig;
import sun.misc.Unsafe;

/**

A FontStrike is the keeper of scaled glyph image data which is expensive
to compute so needs to be cached.
So long as that data may be being used it cannot be invalidated.
Yet we also need to limit the amount of native memory and number of
strike objects in use.
For scaleability and ease of use, a key goal is multi-threaded read
access to a strike, so that it may be shared by multiple client objects,
potentially executing on different threads, with no special reference
counting or "check-out/check-in" requirements which would pass on the
burden of keeping track of strike references to the SG2D and other clients.

A cache of strikes is maintained via Reference objects.
This helps in two ways :
1. The VM will free references when memory is low or they have not been
used in a long time.
2. Reference queues provide a way to get notification of this so we can
free native memory resources.

 */

public final class StrikeCache {

    static final Unsafe unsafe = Unsafe.getUnsafe();

    static ReferenceQueue refQueue = Disposer.getQueue();

    /* Reference objects may have their referents cleared when GC chooses.
     * During application client start-up there is typically at least one
     * GC which causes the hotspot VM to clear soft (not just weak) references
     * Thus not only is there a GC pause, but the work done do rasterise
     * glyphs that are fairly certain to be needed again almost immediately
     * is thrown away. So for performance reasons a simple optimisation is to
     * keep up to 8 strong references to strikes to reduce the chance of
     * GC'ing strikes that have been used recently. Note that this may not
     * suffice in Solaris UTF-8 locales where a single composite strike may be
     * composed of 15 individual strikes, plus the composite strike.
     * And this assumes the new architecture doesn't maintain strikes for
     * natively accessed bitmaps. It may be worth "tuning" the number of
     * strikes kept around for the platform or locale.
     * Since no attempt is made to ensure uniqueness or ensure synchronized
     * access there is no guarantee that this cache will ensure that unique
     * strikes are cached. Every time a strike is looked up it is added
     * to the current index in this cache. All this cache has to do to be
     * worthwhile is prevent excessive cache flushing of strikes that are
     * referenced frequently. The logic that adds references here could be
     * tweaked to keep only strikes  that represent untransformed, screen
     * sizes as that's the typical performance case.
     */
    static int MINSTRIKES = 8; // can be overridden by property
    static int recentStrikeIndex = 0;
    static FontStrike[] recentStrikes;
    static boolean cacheRefTypeWeak;

    /*
     * Native sizes and offsets for glyph cache
     * There are 10 values.
     */
    static int nativeAddressSize;
    static int glyphInfoSize;
    static int xAdvanceOffset;
    static int yAdvanceOffset;
    static int boundsOffset;
    static int widthOffset;
    static int heightOffset;
    static int rowBytesOffset;
    static int topLeftXOffset;
    static int topLeftYOffset;
    static int pixelDataOffset;
    static long invisibleGlyphPtr;

    /* Native method used to return information used for unsafe
     * access to native data.
     * return values as follows:-
     * arr[0] = size of an address/pointer.
     * arr[1] = size of a GlyphInfo
     * arr[2] = offset of advanceX
     * arr[3] = offset of advanceY
     * arr[4] = offset of width
     * arr[5] = offset of height
     * arr[6] = offset of rowBytes
     * arr[7] = offset of topLeftX
     * arr[8] = offset of topLeftY
     * arr[9] = offset of pixel data.
     * arr[10] = address of a GlyphImageRef representing the invisible glyph
     */
    static native void getGlyphCacheDescription(long[] infoArray);

    static {

        long[] nativeInfo = new long[11];
        getGlyphCacheDescription(nativeInfo);
        //Can also get address size from Unsafe class :-
        //nativeAddressSize = unsafe.addressSize();
        nativeAddressSize = (int)nativeInfo[0];
        glyphInfoSize     = (int)nativeInfo[1];
        xAdvanceOffset    = (int)nativeInfo[2];
        yAdvanceOffset    = (int)nativeInfo[3];
        widthOffset       = (int)nativeInfo[4];
        heightOffset      = (int)nativeInfo[5];
        rowBytesOffset    = (int)nativeInfo[6];
        topLeftXOffset    = (int)nativeInfo[7];
        topLeftYOffset    = (int)nativeInfo[8];
        pixelDataOffset   = (int)nativeInfo[9];
        invisibleGlyphPtr = nativeInfo[10];
        if (nativeAddressSize < 4) {
            throw new InternalError("Unexpected address size for font data: " +
                                    nativeAddressSize);
        }

        java.security.AccessController.doPrivileged(
                                    new java.security.PrivilegedAction() {
            public Object run() {

               /* Allow a client to override the reference type used to
                * cache strikes. The default is "soft" which hints to keep
                * the strikes around. This property allows the client to
                * override this to "weak" which hint to the GC to free
                * memory more agressively.
                */
               String refType =
                   System.getProperty("sun.java2d.font.reftype", "soft");
               cacheRefTypeWeak = refType.equals("weak");

                String minStrikesStr =
                    System.getProperty("sun.java2d.font.minstrikes");
                if (minStrikesStr != null) {
                    try {
                        MINSTRIKES = Integer.parseInt(minStrikesStr);
                        if (MINSTRIKES <= 0) {
                            MINSTRIKES = 1;
                        }
                    } catch (NumberFormatException e) {
                    }
                }

                recentStrikes = new FontStrike[MINSTRIKES];

                return null;
            }
        });
    }


    static void refStrike(FontStrike strike) {
        int index = recentStrikeIndex;
        recentStrikes[index] = strike;
        index++;
        if (index == MINSTRIKES) {
            index = 0;
        }
        recentStrikeIndex = index;
    }

    private static final void doDispose(FontStrikeDisposer disposer) {
        if (disposer.intGlyphImages != null) {
            freeIntMemory(disposer.intGlyphImages,
                    disposer.pScalerContext);
        } else if (disposer.longGlyphImages != null) {
            freeLongMemory(disposer.longGlyphImages,
                    disposer.pScalerContext);
        } else if (disposer.segIntGlyphImages != null) {
            /* NB Now making multiple JNI calls in this case.
             * But assuming that there's a reasonable amount of locality
             * rather than sparse references then it should be OK.
             */
            for (int i=0; i<disposer.segIntGlyphImages.length; i++) {
                if (disposer.segIntGlyphImages[i] != null) {
                    freeIntMemory(disposer.segIntGlyphImages[i],
                            disposer.pScalerContext);
                    /* native will only free the scaler context once */
                    disposer.pScalerContext = 0L;
                    disposer.segIntGlyphImages[i] = null;
                }
            }
            /* This may appear inefficient but it should only be invoked
             * for a strike that never was asked to rasterise a glyph.
             */
            if (disposer.pScalerContext != 0L) {
                freeIntMemory(new int[0], disposer.pScalerContext);
            }
        } else if (disposer.segLongGlyphImages != null) {
            for (int i=0; i<disposer.segLongGlyphImages.length; i++) {
                if (disposer.segLongGlyphImages[i] != null) {
                    freeLongMemory(disposer.segLongGlyphImages[i],
                            disposer.pScalerContext);
                    disposer.pScalerContext = 0L;
                    disposer.segLongGlyphImages[i] = null;
                }
            }
            if (disposer.pScalerContext != 0L) {
                freeLongMemory(new long[0], disposer.pScalerContext);
            }
        } else if (disposer.pScalerContext != 0L) {
            /* Rarely a strike may have been created that never cached
             * any glyphs. In this case we still want to free the scaler
             * context.
             */
            if (FontManager.longAddresses) {
                freeLongMemory(new long[0], disposer.pScalerContext);
            } else {
                freeIntMemory(new int[0], disposer.pScalerContext);
            }
        }
    }

    static void disposeStrike(final FontStrikeDisposer disposer) {
        // we need to execute the strike disposal on the rendering thread
        // because they may be accessed on that thread at the time of the
        // disposal (for example, when the accel. cache is invalidated)

        // REMIND: this look a bit heavyweight, but should be ok
        // because strike disposal is a relatively infrequent operation,
        // more worrisome is the necessity of getting a GC here.
        RenderQueue rq = null;
        GraphicsEnvironment ge =
            GraphicsEnvironment.getLocalGraphicsEnvironment();
        if (!ge.isHeadless()) {
            GraphicsConfiguration gc =
                ge.getDefaultScreenDevice().getDefaultConfiguration();
            if (gc instanceof AccelGraphicsConfig) {
                AccelGraphicsConfig agc = (AccelGraphicsConfig)gc;
                BufferedContext bc = agc.getContext();
                if (bc != null) {
                    rq = bc.getRenderQueue();
                }
            }
        }
        if (rq != null) {
            rq.lock();
            try {
                rq.flushAndInvokeNow(new Runnable() {
                    public void run() {
                        doDispose(disposer);
                    }
                });
            } finally {
                rq.unlock();
            }
        } else {
            doDispose(disposer);
        }
    }

    static native void freeIntPointer(int ptr);
    static native void freeLongPointer(long ptr);
    private static native void freeIntMemory(int[] glyphPtrs, long pContext);
    private static native void freeLongMemory(long[] glyphPtrs, long pContext);


    public static Reference getStrikeRef(FontStrike strike) {
        return getStrikeRef(strike, cacheRefTypeWeak);
    }

    public static Reference getStrikeRef(FontStrike strike, boolean weak) {
        /* Some strikes may have no disposer as there's nothing
         * for them to free, as they allocated no native resource
         * eg, if they did not allocate resources because of a problem,
         * or they never hold native resources. So they create no disposer.
         * But any strike that reaches here that has a null disposer is
         * a potential memory leak.
         */
        if (strike.disposer == null) {
            if (weak) {
                return new WeakReference(strike);
            } else {
                return new SoftReference(strike);
            }
        }

        if (weak) {
            return new WeakDisposerRef(strike);
        } else {
            return new SoftDisposerRef(strike);
        }
    }

    static interface DisposableStrike {
        FontStrikeDisposer getDisposer();
    }

    static class SoftDisposerRef
        extends SoftReference implements DisposableStrike {

        private FontStrikeDisposer disposer;

        public FontStrikeDisposer getDisposer() {
            return disposer;
        }

        SoftDisposerRef(FontStrike strike) {
            super(strike, StrikeCache.refQueue);
            disposer = strike.disposer;
            Disposer.addReference(this, disposer);
        }
    }

    static class WeakDisposerRef
        extends WeakReference implements DisposableStrike {

        private FontStrikeDisposer disposer;

        public FontStrikeDisposer getDisposer() {
            return disposer;
        }

        WeakDisposerRef(FontStrike strike) {
            super(strike, StrikeCache.refQueue);
            disposer = strike.disposer;
            Disposer.addReference(this, disposer);
        }
    }

}
