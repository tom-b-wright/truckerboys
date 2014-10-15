package truckerboys.otto.utils.positions;

import android.location.Location;

import com.google.android.gms.maps.model.LatLng;

import org.joda.time.Duration;

/**
 * Created by Daniel on 2014-09-18.
 */
public class MapLocation extends Location{
    private String address = "";
    private Duration eta = Duration.ZERO;

    public MapLocation(Location location){
        super(location);
    }

    public MapLocation(LatLng latLng) {
        super("provider"); //wut?
        setLatitude(latLng.latitude);
        setLongitude(latLng.longitude);
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * Get ETA to location
     * @return ETA to location (ZERO if no ETA is set)
     */
    public Duration getEta() {
        return eta;
    }

    /**
     * Set ETA to location
     * @param eta eta to location
     */
    public void setEta(Duration eta) {
        this.eta = eta;
    }

    /**
     * Checks if the locations have the same coordinates.
     * @param rhs Map location to compare with.
     * @return true if the locations have the same coordinates.
     */
    public boolean equalCoordinates(MapLocation rhs){
        return (rhs.getLatitude() == getLatitude() && rhs.getLongitude() == getLongitude());
    }
}
