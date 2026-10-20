package dev.valkdz.cdisc.feature.karaoke;

final class Biquad {

    private final double b0;
    private final double b1;
    private final double b2;
    private final double a1;
    private final double a2;
    private double z1;
    private double z2;

    private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
        this.b0 = b0 / a0;
        this.b1 = b1 / a0;
        this.b2 = b2 / a0;
        this.a1 = a1 / a0;
        this.a2 = a2 / a0;
    }

    static Biquad lowPass(double hz, int rate) {
        double w = 2 * Math.PI * hz / rate;
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / Math.sqrt(2);
        return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    static Biquad highPass(double hz, int rate) {
        double w = 2 * Math.PI * hz / rate;
        double cos = Math.cos(w);
        double alpha = Math.sin(w) / Math.sqrt(2);
        return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
    }

    double apply(double x) {
        double y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
}
