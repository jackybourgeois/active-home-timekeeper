package org.activehome.timekeeper;

/*
 * #%L
 * Active Home :: Timekeeper
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 Active Home Project
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import com.eclipsesource.json.JsonObject;
import org.activehome.com.Notif;
import org.activehome.com.Request;
import org.activehome.com.ShowIfErrorCallback;
import org.activehome.com.Status;
import org.activehome.context.data.DataPoint;
import org.activehome.service.Service;
import org.activehome.service.RequestHandler;
import org.activehome.time.Tic;
import org.activehome.time.TimeCommand;
import org.activehome.time.TimeStatus;
import org.kevoree.annotation.ComponentType;
import org.kevoree.annotation.KevoreeInject;
import org.kevoree.annotation.Output;
import org.kevoree.annotation.Param;
import org.kevoree.annotation.Start;
import org.kevoree.annotation.Stop;
import org.kevoree.api.ModelService;
import org.kevoree.api.handler.ModelListener;
import org.kevoree.log.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Manage the time for sync of distributed components and simulation.
 *
 * @author Jacky Bourgeois
 */
@ComponentType(version = 1, description = "Manage the time for sync of "
        + "distributed components and simulation.")
public class Timekeeper extends Service implements ModelListener {

    /**
     * Where to find the sources (for the Active Home store).
     */
    @Param(defaultValue = "/active-home-timekeeper")
    private String src;
    /**
     * The start time of the system.
     * By default, use the actual/current time.
     */
    @Param(defaultValue = "actual")
    private String startDate;
    /**
     * Factor of time compression.
     * x1 (default) means actual speed
     */
    @Param(defaultValue = "x1")
    private String zipFactor;
    /**
     * Timezone of the timekeeper.
     */
    @Param(defaultValue = "Europe/London")
    private String timezoneName;
    /**
     * Latitude of the timekeeper (to retrieve geographical
     * info such as sunrise and sunset).
     */
    @Param(defaultValue = "52.041404")
    private double latitude;
    /**
     * Longitude of the timekeeper (to retrieve geographical
     * info such as sunrise and sunset).
     */
    @Param(defaultValue = "-0.72878")
    private double longitude;
    /**
     * Generate a log for each tic.
     */
    @Param(defaultValue = "false")
    private boolean showTic;

    /**
     * Port to push the {@code Tic}.
     */
    @Output
    private org.kevoree.api.Port tic;
    /**
     * Access to Kevoree's model.
     */
    @KevoreeInject
    private ModelService modelService;

    /**
     * Keep the current {@link TimeStatus}.
     */
    private TimeStatus status = TimeStatus.UNKNOWN;
    /**
     * Time zone of the system.
     */
    private TimeZone timezone;
    /**
     * Time initialized when calling {@link #init}.
     */
    private long startTS = -1;
    /**
     * Actual system time used to compute the delta
     * with Active Home time (same as start time if actual time).
     */
    private long initTS;
    /**
     * Time spent on pause to add to time delta in simulation mode.
     * (Combining all idle time since start time)
     */
    private long idleDuration = 0;
    /**
     * When the current pause started.
     */
    private long pauseTS;
    /**
     * The time compression factor as number.
     */
    private int zip = 1;
    /**
     * The tic frequency as number.
     */
    private long ticFrequency = HOUR;
    /**
     * Date/time format used for time parameters.
     */
    private SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    /**
     * Is it currently day time?
     */
    private Boolean dayTime;
    /**
     * Scheduler used to send Tics.
     */
    private ScheduledThreadPoolExecutor stpe;

    @Override
    protected final RequestHandler getRequestHandler(final Request request) {
        return new TimekeeperRequestHandler(this);
    }

    /**
     * Start the component, reading Kevoree params to set the
     * timezone, start time, zip, and tic frequency.
     */
    @Start
    public final void start() {
        super.start();
        modelService.registerModelListener(this);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        timezone = TimeZone.getTimeZone(timezoneName);
        setStartTime(startDate);
        setZip(zipFactor);
        setTicFrequency();
    }

    /**
     * Switch the status to INITIALIZED and send a Tic
     * which contains the time command INIT.
     *
     * @return true
     */
    final boolean init() {
        status = TimeStatus.INITIALIZED;
        initExecutor();
        sendTic(new Tic(getUTCTime(), zip, getTimezoneOffset(),
                status, TimeCommand.INIT));
        if (startTS == -1) {
            startTime();
        }
        return true;
    }

    /**
     * Start sending Tic at the given time zip and frequency.
     * The first Tic contains the time command START.
     *
     * @return true if the time was initialize before
     */
    final boolean startTime() {
        if (status == TimeStatus.INITIALIZED) {
            initTS = System.currentTimeMillis();
            idleDuration = 0;
            status = TimeStatus.RUNNING;
            sendTic(new Tic(getUTCTime(), zip, getTimezoneOffset(),
                    status, TimeCommand.START));
            stpe.scheduleAtFixedRate(this::tic, ticFrequency,
                    ticFrequency, TimeUnit.MILLISECONDS);
            checkSunsetSunrise();
            return true;
        }
        return false;
    }

    /**
     * Stop sending regular Tics with a last one containing
     * the time command PAUSE.
     *
     * @return true if the time was running
     */
    final boolean pauseTime() {
        if (startTS != -1) {
            if (status == TimeStatus.RUNNING) {
                logInfo("Pausing time.");
                stpe.shutdownNow();
                pauseTS = System.currentTimeMillis();
                status = TimeStatus.IDLE;
                sendTic(new Tic(getUTCTime(), zip,
                        getTimezoneOffset(), status, TimeCommand.PAUSE));
                return true;
            }
        } else {
            Log.warn("You cannot control the actual time dude! (pauseTime)");
        }
        return false;
    }

    /**
     * Restart sending regular Tics with the first one containing
     * the time command RESUME.
     *
     * @return true if the time was idled
     */
    final boolean resumeTime() {
        if (startTS != -1) {
            if (status == TimeStatus.IDLE) {
                logInfo("Resuming time.");
                status = TimeStatus.RUNNING;
                initExecutor();
                idleDuration += System.currentTimeMillis() - pauseTS;
                sendTic(new Tic(getUTCTime(), zip, getTimezoneOffset(),
                        status, TimeCommand.RESUME));
                stpe.scheduleAtFixedRate(this::tic, ticFrequency,
                        ticFrequency, TimeUnit.MILLISECONDS);
                checkSunsetSunrise();
                return true;
            }
        } else {
            Log.warn("You cannot control the actual time dude! (resumeTime)");
        }
        return false;
    }

    /**
     * Stop sending regular Tics with a last one containing
     * the time command STOP.
     *
     * @return true if time was running or idled
     */
    final boolean stopTime() {
        if (startTS != -1) {
            if (status == TimeStatus.RUNNING || status == TimeStatus.IDLE) {
                logInfo("Stopping time.");
                pauseTS = System.currentTimeMillis();
                status = TimeStatus.STOPPED;
                sendTic(new Tic(getUTCTime(), zip,
                        getTimezoneOffset(), status, TimeCommand.STOP));
                stpe.shutdownNow();
                return true;
            }
        } else {
            Log.warn("You cannot control the actual time! (stopTime)");
        }
        return false;
    }

    /**
     * Send Tic through tic port.
     *
     * @param aTic to send
     */
    private void sendTic(final Tic aTic) {
        if (tic != null && tic.getConnectedBindingsSize() > 0) {
            tic.send(aTic.toString(), null);
        }
    }

    /**
     *
     */
    private void tic() {
        if (showTic) {
            logInfo("Tic");
        }
        if (tic != null && tic.getConnectedBindingsSize() > 0) {
            Tic nextTic = new Tic(getUTCTime(), zip,
                    getTimezoneOffset(), status, TimeCommand.CARRYON);
            tic.send(nextTic.toString(), null);
        }
    }

    /**
     *
     */
    private void checkSunsetSunrise() {
        SunsetSunrise sunsetSunrise = new SunsetSunrise(latitude, longitude,
                new Date(getUTCTime()), getTimezoneOffset());
        if (dayTime == null || !dayTime.equals(sunsetSunrise.isDaytime())) {
            dayTime = sunsetSunrise.isDaytime();
            DataPoint dpDayTime = new DataPoint("time.dayTime", getUTCTime(),
                    dayTime + "");
            sendNotif(new Notif(getFullId(), getNode() + ".context",
                    getUTCTime(), dpDayTime));
        }

        long nextCheck;
        if (getUTCTime() < sunsetSunrise.getSunrise().getTime()) {
            nextCheck = (sunsetSunrise.getSunrise().getTime() - getUTCTime());
        } else if (dayTime) {
            nextCheck = (sunsetSunrise.getSunset().getTime() - getUTCTime());
        } else {
            SunsetSunrise nextSS = new SunsetSunrise(latitude, longitude,
                    new Date(getUTCTime() + DAY), getTimezoneOffset());
            nextCheck = (nextSS.getSunrise().getTime() - getUTCTime());
        }
        stpe.schedule(this::checkSunsetSunrise,
                nextCheck / zip, TimeUnit.MILLISECONDS);
    }

    /**
     * Get the current UTC time: emulated idle/running or actual.
     *
     * @return UNIX timestamp.
     */
    public final long getUTCTime() {
        if (startTS != -1) {
            if (status == TimeStatus.RUNNING) {
                return startTS + (System.currentTimeMillis()
                        - initTS - idleDuration) * zip;
            } else if (status == TimeStatus.IDLE
                    || status == TimeStatus.STOPPED) {
                return startTS + (pauseTS - initTS - idleDuration) * zip;
            }
            return startTS;
        }
        return System.currentTimeMillis();
    }

    /**
     * @return the number of hours to shift in order
     * to get the time zone from UTC time.
     */
    private int getTimezoneOffset() {
        return (int) (timezone.getOffset(getUTCTime()) / HOUR);
    }

    /**
     * Set new time properties and call {@code #init}.
     *
     * @param properties The new properties to set up.
     * @return {@code #getProperties} showing the new status of all properties.
     */
    public final JsonObject setProperties(final JsonObject properties) {
        if (properties.get("timezone") != null) {
            timezone = TimeZone.getTimeZone(
                    properties.get("timezone").asString());
        }

        if (properties.get("start") != null) {
            if (properties.get("start").isString()) {
                setStartTime(properties.get("start").asString());
            } else if (properties.get("start").isNumber()) {
                startTS = properties.get("start").asLong();
            }
        }

        if (properties.get("zip").isString()) {
            setZip(properties.get("zip").asString());
        } else if (properties.get("zip").isNumber()) {
            setZip(properties.get("zip").asDouble() + "");
        }

        setTicFrequency();
        init();

        return getProperties();
    }

    /**
     * Set the start time to a specific time, current time
     * or 0 otherwise (parsing error with log).
     *
     * @param start local date/time as:
     *              'yyyy-MM-dd HH:mm:ss' to set a specific date
     *              'actual' to use the JVM time
     */
    private void setStartTime(final String start) {
        if (start.compareTo("actual") == 0) {
            startTS = System.currentTimeMillis();
        } else {
            try {
                long localStart = df.parse(start).getTime();
                startTS = localStart - timezone.getOffset(localStart);
            } catch (ParseException e) {
                startTS = 0;
                logError("Could not parse start parameter '"
                        + start + "', Set 0 instead.");
            }
        }
    }

    /**
     * Set the time compression factor. The String can be a number
     * alone or starting with 'x'.
     *
     * @param aZipFactor the time compression factor
     */
    private void setZip(final String aZipFactor) {
        try {
            zip = Integer.valueOf(aZipFactor.replace("x", ""));
            if (zip == 0) {
                zip = 1;
            }
        } catch (NumberFormatException e) {
            zip = 1;
        }
    }

    /**
     * Set the tic frequency to one tic per hour.
     * (in simulation time)
     */
    private void setTicFrequency() {
        ticFrequency = HOUR / zip;
    }

    /**
     * @return return a Json with the start date, zip and status.
     */
    public final JsonObject getProperties() {
        JsonObject prop = new JsonObject();
        prop.add("startDate", startTS);
        prop.add("zip", zip);
        prop.add("status", status.name());
        return prop;
    }

    /**
     * When stopping the component, stop the scheduled thread.
     */
    @Stop
    public final void stop() {
        if (stpe != null) {
            stpe.shutdownNow();
        }
    }

    @Override
    public final void modelUpdated() {
        if (isFirstModelUpdate()) {
            sendRequest(new Request(getFullId(),
                            getNode() + ".http", getCurrentTime(),
                            "addHandler",
                            new Object[]{"/timekeeper", getFullId(), true}),
                    new ShowIfErrorCallback());
        }
        if (status == null || status.equals(Status.UNKNOWN)) {
            init();
        } else {
            sendTic(new Tic(getUTCTime(), zip,
                    getTimezoneOffset(), status, TimeCommand.CARRYON));
        }
        super.modelUpdated();
    }

    /**
     * Create a dedicated thread for scheduled events.
     */
    private void initExecutor() {
        stpe = new ScheduledThreadPoolExecutor(1, r -> {
            return new Thread(r, getFullId() + "-timekeeper-pool");
        });
    }

}



