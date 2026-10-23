package com.metallum.client.metal.render;

/** Domain comes from the texture channel, not from the overlay/reactive wire tag. */
final class MetalMotionPixelCoverage {
    static boolean hudCovered(int hudTag,float hudDepth) {return hudTag>31 || hudDepth>0.0000001F;}
    static boolean exactWorldOutsideHud(int worldTag,int hudTag,float hudDepth) {
        return (worldTag==159 || worldTag==191 || worldTag==223 || worldTag==255) && !hudCovered(hudTag,hudDepth);
    }
    static boolean reactiveWorldOutsideHud(int worldTag,int hudTag,float hudDepth) {
        return (worldTag==159 || worldTag==191) && !hudCovered(hudTag,hudDepth);
    }
    private MetalMotionPixelCoverage() {}
}
