package com.yahooeu2k.dlb_charging;

import android.app.Application;

public class DlbApplication extends Application {
    private VehiclePropertyObserver vehiclePropertyObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        vehiclePropertyObserver = new VehiclePropertyObserver(this);
        vehiclePropertyObserver.start();
    }

    public VehiclePropertyObserver getVehiclePropertyObserver() {
        return vehiclePropertyObserver;
    }
}
