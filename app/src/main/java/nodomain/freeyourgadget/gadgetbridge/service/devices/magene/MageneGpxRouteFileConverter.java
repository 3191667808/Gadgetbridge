package nodomain.freeyourgadget.gadgetbridge.service.devices.magene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.proto.magene.MageneRoute;
import nodomain.freeyourgadget.gadgetbridge.util.GB;
import nodomain.freeyourgadget.gadgetbridge.util.gpx.model.GpxFile;
import nodomain.freeyourgadget.gadgetbridge.util.gpx.model.GpxTrack;
import nodomain.freeyourgadget.gadgetbridge.util.gpx.model.GpxTrackPoint;
import nodomain.freeyourgadget.gadgetbridge.util.gpx.model.GpxTrackSegment;


public class MageneGpxRouteFileConverter {
    private static final Logger LOG = LoggerFactory.getLogger(MageneGpxRouteFileConverter.class);

    private static final double PRECISION = Math.pow(2.0d, 31.0d);

    // Consider making these configurable or passed in if they vary
    private static final double INTERPOLATE_DISTANCE_MAX = 100.0d;
    private static final double INTERPOLATE_DISTANCE_MIN = 50.0d;
    private static final double AVERAGE_SPEED_MPS = 5.0d; // meters per second

    private final GpxFile gpxFile;
    private final String trackName; // Name for the route, can be from GpxFile or provided
    private int calculatedNumberOfSegments = 0; // Stores the number of segments processed
    private int middlePointLatitudeEncoded = 0;
    private int middlePointLongitudeEncoded = 0;

    public MageneGpxRouteFileConverter(GpxFile gpxFile, String trackName) {
        if (gpxFile == null) {
            throw new IllegalArgumentException("GpxFile cannot be null");
        }
        this.gpxFile = gpxFile;
        this.trackName = (trackName == null || trackName.isEmpty()) ? "Unnamed Track" : trackName;
    }

    /**
     * Converts the provided GpxFile into the Magene route binary payload.
     *
     * @return byte[] containing the Magene route payload, or null if no valid track data is found.
     * @throws IOException if there's an issue writing to the ByteArrayOutputStream.
     */
    public byte[] convertToPayload() throws IOException {
        this.calculatedNumberOfSegments = 0; // Reset for current conversion
        this.middlePointLatitudeEncoded = 0; // Reset for current conversion
        this.middlePointLongitudeEncoded = 0; // Reset for current conversion

        List<double[]> allTrackPoints = extractAllTrackPoints(gpxFile);

        if (allTrackPoints.isEmpty()) {
            System.out.println("No track points found in GpxFile. Cannot create a route.");
            return null;
        }

        // Calculate middle point for encoded lat/lon getters
        double[] middlePoint = allTrackPoints.get(allTrackPoints.size() / 2);
        this.middlePointLatitudeEncoded = (int) ((middlePoint[1] * PRECISION) / 180.0d); // lat is at index 1
        this.middlePointLongitudeEncoded = (int) ((middlePoint[0] * PRECISION) / 180.0d); // lon is at index 0


        if (allTrackPoints.size() < 2) {
            System.out.println("Less than 2 points found in GpxFile. Cannot create a route with segments.");
            return null; // A route needs at least two points to form a segment
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // Process each segment between original GPX points
            for (int i = 0; i < allTrackPoints.size() - 1; i++) {
                double[] startPoint = allTrackPoints.get(i); // [lon, lat]
                double[] endPoint = allTrackPoints.get(i + 1); // [lon, lat]

                // Determine interpolation distance
                // Use MIN for the very last segment of the entire track, MAX otherwise
                double interpolationDistance = (i == allTrackPoints.size() - 2) ? INTERPOLATE_DISTANCE_MIN : INTERPOLATE_DISTANCE_MAX;

                // Interpolate points for the current segment
                List<double[]> interpolatedPoints = routes_interp(startPoint, endPoint, interpolationDistance);

                MageneRoute.RoadPlan.Builder roadPlanBuilder = MageneRoute.RoadPlan.newBuilder();

                // Build and set StepOrigin details
                MageneRoute.StepSourceLocation.Builder stepBuilder = MageneRoute.StepSourceLocation.newBuilder();
                stepBuilder.setRoadName(this.trackName); // Use the provided track name

                // Calculate distance and duration for the current segment
                // This segment goes from startPoint -> all interpolatedPoints -> endPoint
                double segmentTotalDistance = get_distance(BL2Mercator(startPoint[0], startPoint[1]), BL2Mercator(endPoint[0], endPoint[1]));;
//                double[] prevCalcPoint = startPoint;

//                for (double[] interpPoint : interpolatedPoints) {
//                    segmentTotalDistance += get_distance(BL2Mercator(prevCalcPoint[0], prevCalcPoint[1]), BL2Mercator(interpPoint[0], interpPoint[1]));
//                    prevCalcPoint = interpPoint;
//                }
                // Add distance from last interpolated point to the actual endPoint of the segment
                //segmentTotalDistance += get_distance(BL2Mercator(prevCalcPoint[0], prevCalcPoint[1]), BL2Mercator(endPoint[0], endPoint[1]));

                int segmentTotalDuration = (int) (segmentTotalDistance / AVERAGE_SPEED_MPS);
                if (segmentTotalDuration < 0) segmentTotalDuration = 0; // Ensure non-negative

                stepBuilder.setDistance((int) segmentTotalDistance);
                stepBuilder.setDuration(segmentTotalDuration);

                // Set Origin location (startPoint of the current original segment)
                MageneRoute.PathType.Builder originBuilder = MageneRoute.PathType.newBuilder();
                originBuilder.setLatitude((int) ((startPoint[1] * PRECISION) / 180.0d)); // lat is at index 1
                originBuilder.setLongitude((int) ((startPoint[0] * PRECISION) / 180.0d)); // lon is at index 0
                originBuilder.setHeight(0).setUnk2(0).setLen(0); // FIXME: set real altitude and distance
                stepBuilder.setOriginLocation(originBuilder.build());

                // Set Destination location (endPoint of the current original segment)
                MageneRoute.PathType.Builder destBuilder = MageneRoute.PathType.newBuilder();
                destBuilder.setLatitude((int) ((endPoint[1] * PRECISION) / 180.0d));   // lat is at index 1
                destBuilder.setLongitude((int) ((endPoint[0] * PRECISION) / 180.0d));  // lon is at index 0
                destBuilder.setHeight(0).setUnk2(0).setLen(0); // FIXME: set real altitude and distance
                stepBuilder.setDestinationlocation(destBuilder.build());

                // Set dummy direction info
                MageneRoute.DestinationType.Builder destTypeBuilder = MageneRoute.DestinationType.newBuilder();
                if (i == allTrackPoints.size() - 2) { // Check if this is the last segment of the entire track
                    destTypeBuilder.setDestType(2); // Mark as final destination type
                } else {
                    destTypeBuilder.setDestType(1); // Default/Unknown for intermediate segments
                }
                destTypeBuilder.setDestDirect(0); // Default/Unknown
                stepBuilder.setDirectioninfor(destTypeBuilder.build());

                roadPlanBuilder.setStepOrigin(stepBuilder.build());

                // Add the start point of this road plan (which is startPoint)
                MageneRoute.PathType.Builder startPathBuilder = MageneRoute.PathType.newBuilder();
                startPathBuilder.setLatitude((int) ((startPoint[1] * PRECISION) / 180.0d));
                startPathBuilder.setLongitude((int) ((startPoint[0] * PRECISION) / 180.0d));
                startPathBuilder.setHeight(0).setUnk2(0).setLen(0); // FIXME: set real altitude and distance
                roadPlanBuilder.addPath(startPathBuilder.build());

                for (double[] point : interpolatedPoints) {
                    MageneRoute.PathType.Builder pathBuilder = MageneRoute.PathType.newBuilder();
                    pathBuilder.setLatitude((int) ((point[1] * PRECISION) / 180.0d));  // lat is at index 1
                    pathBuilder.setLongitude((int) ((point[0] * PRECISION) / 180.0d)); // lon is at index 0
                    pathBuilder.setHeight(0).setUnk2(0).setLen(0); // FIXME: set real altitude and distance
                    roadPlanBuilder.addPath(pathBuilder.build());
                }

                // Add the end point of this road plan (which is endPoint)
                MageneRoute.PathType.Builder endPathBuilder = MageneRoute.PathType.newBuilder();
                endPathBuilder.setLatitude((int) ((endPoint[1] * PRECISION) / 180.0d));
                endPathBuilder.setLongitude((int) ((endPoint[0] * PRECISION) / 180.0d));
                endPathBuilder.setHeight(0).setUnk2(0).setLen(0); // FIXME: set real altitude and distance
                roadPlanBuilder.addPath(endPathBuilder.build());

                roadPlanBuilder.setPathSize(roadPlanBuilder.getPathCount());

                // Serialize and write this segment's RoadPlan to the output stream
                byte[] protobufData = roadPlanBuilder.build().toByteArray();
                byte[] sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(protobufData.length).array();
                outputStream.write(sizeBytes);
                outputStream.write(protobufData);
                LOG.info("encoded segment: " + GB.hexdump(protobufData));


                this.calculatedNumberOfSegments++;
            }

            System.out.println("Route encoded successfully to byte array for track: " + this.trackName);
            return outputStream.toByteArray();

        } catch (Exception e) { // Catch broader exceptions during processing
            // Log the error or handle it more gracefully
            System.err.println("Error during Magene route conversion: " + e.getMessage());
            e.printStackTrace(); // For detailed debugging
            this.calculatedNumberOfSegments = 0; // Reset on error
            this.middlePointLatitudeEncoded = 0; // Reset on error
            this.middlePointLongitudeEncoded = 0; // Reset on error
            throw new IOException("Failed to convert GPX to Magene payload: " + e.getMessage(), e); // Re-throw as IOException or custom exception
        }
    }

    /**
     * Returns the number of segments that were processed in the last call to convertToPayload().
     * A segment is defined as the part of a route between two consecutive original GPX track points.
     * If convertToPayload() has not been called, or if it failed or found no valid track data,
     * this will return 0.
     *
     * @return The number of original GPX segments processed.
     */
    public int getNumberOfSegments() {
        return this.calculatedNumberOfSegments;
    }

    /**
     * Returns the encoded latitude of the middle point of the GPX track.
     * This value is calculated during convertToPayload().
     * Returns 0 if convertToPayload() has not been called, failed, or no track points were found.
     *
     * @return The encoded latitude of the middle track point.
     */
    public int getRouteLatitude() {
        return this.middlePointLatitudeEncoded;
    }

    /**
     * Returns the encoded longitude of the middle point of the GPX track.
     * This value is calculated during convertToPayload().
     * Returns 0 if convertToPayload() has not been called, failed, or no track points were found.
     *
     * @return The encoded longitude of the middle track point.
     */
    public int getRouteLongitude() {
        return this.middlePointLongitudeEncoded;
    }

    /**
     * Extracts all track points (as [lon, lat] arrays) from the GpxFile.
     * It concatenates points from all tracks and segments.
     */
    private List<double[]> extractAllTrackPoints(GpxFile gpxFile) {
        List<double[]> points = new ArrayList<>();
        if (gpxFile.getTracks() == null) {
            return points;
        }

        for (GpxTrack track : gpxFile.getTracks()) {
            if (track.getTrackSegments() == null) {
                continue;
            }
            for (GpxTrackSegment segment : track.getTrackSegments()) {
                if (segment.getTrackPoints() == null) {
                    continue;
                }
                for (GpxTrackPoint wp : segment.getTrackPoints()) {
                    points.add(new double[]{wp.getLongitude(), wp.getLatitude()});
                }
            }
        }
        return points;
    }

    // --- Helper methods (routes_interp, get_distance, get_heading, BL2Mercator, Mercator2BL) remain the same ---
    // Make them private if not used outside this class, or keep static if they are general utilities

    private static List<double[]> routes_interp(double[] dArr, double[] dArr2, double d) {
        ArrayList<double[]> arrayList = new ArrayList<>();
        double[] mercatorStart = BL2Mercator(dArr[0], dArr[1]); // lon, lat
        double[] mercatorEnd = BL2Mercator(dArr2[0], dArr2[1]);   // lon, lat

        double d2 = get_distance(mercatorStart, mercatorEnd);
        if (d2 > d && d > 0) { // Also check d > 0 to avoid division by zero or infinite loop
            double d3 = get_heading(mercatorStart, mercatorEnd);
            double ceil = Math.ceil(d2 / d); // Number of new segments to create
            double d4 = d2 / ceil; // Actual distance for each new segment

            for (int i = 1; i < ((int) ceil); i++) { // Iterate to create (ceil - 1) intermediate points
                double d5 = i * d4; // Distance from mercatorStart along the heading
                double[] dArr3 = {mercatorStart[0] + (Math.cos(d3) * d5), mercatorStart[1] + (d5 * Math.sin(d3))};
                arrayList.add(Mercator2BL(dArr3[0], dArr3[1]));
            }
        }
        return arrayList;
    }



    private static double get_distance(double[] p1Mercator, double[] p2Mercator) {
        return Math.sqrt(Math.pow(p2Mercator[0] - p1Mercator[0], 2) + Math.pow(p2Mercator[1] - p1Mercator[1], 2));
    }

    private static double get_heading(double[] p1Mercator, double[] p2Mercator) {
        return Math.atan2(p2Mercator[1] - p1Mercator[1], p2Mercator[0] - p1Mercator[0]);
    }

    private static double[] BL2Mercator(double lon, double lat) {
        double mercatorX = Math.toRadians(lon) * 6378137.0d;
        double mercatorY = Math.log(Math.tan(Math.toRadians(lat) / 2.0d + Math.PI / 4.0d)) * 6378137.0d;
        return new double[]{mercatorX, mercatorY};
    }

    private static double[] Mercator2BL(double mercatorX, double mercatorY) {
        double lon = Math.toDegrees(mercatorX / 6378137.0d);
        double lat = Math.toDegrees(2.0d * Math.atan(Math.exp(mercatorY / 6378137.0d)) - Math.PI / 2.0d);
        return new double[]{lon, lat};
    }

}
