package io.github.osir933.anchor.core.physics.thermal;

/**
 * The sky over a world at one moment: where the sun is and what the weather is doing. A host sets it each step
 * from its own clock and weather.
 *
 * @param sunX the east-west part of the unit vector from the ground towards the sun
 * @param sunY its upward part, the sine of the sun's height above the horizon; zero or less at night
 * @param sunZ its north-south part
 * @param cloudCover the fraction of the sky covered by cloud, from 0 for clear to 1 for overcast
 * @param precipitation how hard it rains or snows, from 0 for dry to 1 for steady rain; the air grows more humid
 * @param storm how stormy the weather is, from 0 to 1; thunderclouds let through far less sunlight than ordinary
 *     overcast
 */
public record Sky(double sunX, double sunY, double sunZ, double cloudCover, double precipitation, double storm) {

    /**
     * Validates the sky and makes the sun's direction a unit vector.
     *
     * @param sunX the direction's east-west part
     * @param sunY its upward part
     * @param sunZ its north-south part
     * @param cloudCover the cloud cover
     * @param precipitation the precipitation
     * @param storm the storminess
     */
    public Sky {
        double length = Math.sqrt(sunX * sunX + sunY * sunY + sunZ * sunZ);
        if (!(length > 0) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("the sun needs a direction: " + sunX + ", " + sunY + ", " + sunZ);
        }
        sunX /= length;
        sunY /= length;
        sunZ /= length;
        check("cloud cover", cloudCover);
        check("precipitation", precipitation);
        check("storm", storm);
    }

    private static void check(String name, double value) {
        if (!(value >= 0 && value <= 1)) {
            throw new IllegalArgumentException(name + " must lie between 0 and 1: " + value);
        }
    }

    /**
     * Returns a cloudless, dry sky with the sun on a path that rises due east, passes overhead and sets due west,
     * as Minecraft's sun does.
     *
     * @param degreesAboveEast the sun's angle above the eastern horizon: 0 at sunrise, 90 at noon, 180 at sunset,
     *     and below the horizon outside that range
     * @return the sky
     */
    public static Sky clear(double degreesAboveEast) {
        double angle = StrictMath.toRadians(degreesAboveEast);
        return new Sky(StrictMath.cos(angle), StrictMath.sin(angle), 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Returns this sky with different weather.
     *
     * @param newCloudCover the cloud cover
     * @param newPrecipitation the precipitation
     * @param newStorm the storminess
     * @return the new sky
     */
    public Sky withWeather(double newCloudCover, double newPrecipitation, double newStorm) {
        return new Sky(sunX, sunY, sunZ, newCloudCover, newPrecipitation, newStorm);
    }

    /**
     * Tells whether the sun is above the horizon.
     *
     * @return {@code true} by day
     */
    public boolean sunUp() {
        return sunY > 0;
    }
}
