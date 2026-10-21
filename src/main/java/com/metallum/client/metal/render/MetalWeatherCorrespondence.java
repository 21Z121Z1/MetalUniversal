package com.metallum.client.metal.render;

import java.util.Arrays;

/** Correspondence of the actual weather quad's sampled pattern, including UV scrolling. */
final class MetalWeatherCorrespondence {
    private MetalWeatherCorrespondence() {}

    record Column(long id,double time,long phaseEpoch,float uOffset,float vOffset,
                  double uRate,double vRate,float[] positions,float[] uv) {
        Column {
            if(positions.length!=12 || uv.length!=8)throw new IllegalArgumentException("Weather columns require four XYZ/UV vertices");
            positions=positions.clone();uv=uv.clone();
        }
        @Override public float[] positions() {return positions.clone();}
        @Override public float[] uv() {return uv.clone();}

        private boolean finite() {
            if(!Double.isFinite(time)||!Float.isFinite(uOffset)||!Float.isFinite(vOffset)
                    || !Double.isFinite(uRate)||!Double.isFinite(vRate))return false;
            for(float value:positions)if(!Float.isFinite(value))return false;
            for(float value:uv)if(!Float.isFinite(value))return false;
            return parallelogram(positions,3) && parallelogram(uv,2);
        }
    }

    record Quad(float[] positions,float[] previousCoverage,boolean hasCorrespondence) {
        Quad {positions=positions.clone();previousCoverage=previousCoverage.clone();}
        @Override public float[] positions() {return positions.clone();}
        @Override public float[] previousCoverage() {return previousCoverage.clone();}
    }

    /**
     * The address-mode period is 1 for repeat, 2 for mirrored repeat, 0 for a nonperiodic sampler.
     * Old local UV stays unclamped: the fragment must reject parts outside the old emitted quad.
     */
    static Quad reproject(Column previous,Column current,double uPeriod,double vPeriod) {
        if(previous==null || current==null || previous.id!=current.id || !previous.finite() || !current.finite()
                || current.time<previous.time || current.phaseEpoch!=previous.phaseEpoch
                || current.uRate!=previous.uRate || current.vRate!=previous.vRate
                || !Double.isFinite(uPeriod)||!Double.isFinite(vPeriod)||uPeriod<0||vPeriod<0)return invalid();
        double dt=current.time-previous.time;
        double uShift=periodShift(current.uOffset-previous.uOffset,current.uRate*dt,uPeriod);
        double vShift=periodShift(current.vOffset-previous.vOffset,current.vRate*dt,vPeriod);
        // Actual UV basis from vertices 0,1,3. This also accounts for changing top/bottom bounds.
        double ux=previous.uv[2]-previous.uv[0],uy=previous.uv[3]-previous.uv[1];
        double vx=previous.uv[6]-previous.uv[0],vy=previous.uv[7]-previous.uv[1];
        double determinant=ux*vy-uy*vx;
        if(!Double.isFinite(determinant)||Math.abs(determinant)<1e-12)return invalid();
        float[] positions=new float[12],coverage=new float[8];
        for(int vertex=0;vertex<4;vertex++) {
            double du=current.uv[vertex*2]+uShift-previous.uv[0];
            double dv=current.uv[vertex*2+1]+vShift-previous.uv[1];
            double u=(du*vy-dv*vx)/determinant,v=(dv*ux-du*uy)/determinant;
            coverage[vertex*2]=(float)u;coverage[vertex*2+1]=(float)v;
            if(!Float.isFinite(coverage[vertex*2])||!Float.isFinite(coverage[vertex*2+1]))return invalid();
            for(int axis=0;axis<3;axis++) {
                positions[vertex*3+axis]=(float)(previous.positions[axis]
                        +u*(previous.positions[3+axis]-previous.positions[axis])
                        +v*(previous.positions[9+axis]-previous.positions[axis]));
                if(!Float.isFinite(positions[vertex*3+axis]))return invalid();
            }
        }
        return new Quad(positions,coverage,true);
    }

    private static double periodShift(double actualDelta,double expectedDelta,double period) {
        return period==0 ? 0 : Math.rint((expectedDelta-actualDelta)/period)*period;
    }

    private static boolean parallelogram(float[] vertices,int components) {
        for(int axis=0;axis<components;axis++) {
            double expected=(double)vertices[components+axis]+vertices[3*components+axis]-vertices[axis];
            float magnitude=0;for(int vertex=0;vertex<4;vertex++)magnitude=Math.max(magnitude,Math.abs(vertices[vertex*components+axis]));
            if(Math.abs(expected-vertices[2*components+axis])>4.0*Math.ulp(magnitude))return false;
        }
        return true;
    }

    private static Quad invalid() {
        // CPU sentinel only; the production upload writes finite XYZ and an explicit validity bit.
        float[] positions=new float[12];Arrays.fill(positions,Float.NaN);
        return new Quad(positions,new float[8],false);
    }
}
