package org.activehome.timekeeper;

/*
 * #%L
 * Active Home :: :: Timekeeper
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 org.activehome
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
import org.activehome.tools.SunsetSunrise;
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
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
@ComponentType
public class TimeKeeper extends Service implements ModelListener {

    private TimeStatus status;
    private TimeZone timezone;
    private long startTS;
    private long initTS;
    private long idleDuration;
    private long pauseTS;
    private int zip;
    private long ticFrequency;
    private SimpleDateFormat df;

    private Boolean dayTime;

    private ScheduledThreadPoolExecutor stpe;

    @Param(defaultValue = "")
    private String startDate;
    @Param(defaultValue = "x1")
    private String zipFactor;
    @Param(defaultValue = "Europe/London")
    private String timezoneName;
    @Param(defaultValue = "false")
    private boolean showTic;

    @Output
    private
    org.kevoree.api.Port tic;

    @KevoreeInject
    private ModelService modelService;

    @Override
    protected final RequestHandler getRequestHandler(final Request request) {
        return new TimeKeeperRequestHandler(request, this);
    }

    public final boolean init() {
        status = TimeStatus.INITIALIZED;
        initExecutor();
        sendTic(new Tic(getUTCTime(), zip, getTimezoneOffset(),
                status, TimeCommand.INIT));
        if (startTS == -1) {
            startTime();
        }
        return true;
    }

    @Start
    public final void start() {
        super.start();
        modelService.registerModelListener(this);
        df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        idleDuration = 0;
        timezone = TimeZone.getTimeZone(timezoneName);
        if (startDate.compareTo("actual") != 0) {
            try {
                long localStartTime = df.parse(startDate).getTime();
                startTS = localStartTime - timezone.getOffset(localStartTime);
                zip = Integer.valueOf(zipFactor.replace("x", ""));
                ticFrequency = HOUR / zip;
            } catch (Exception e) {
                startTS = System.currentTimeMillis();
                zip = 1;
                ticFrequency = HOUR;
            }
            status = TimeStatus.UNKNOWN;
        } else {
            startTS = -1;
            zip = 1;
            ticFrequency = HOUR;
        }
    }

    public final boolean startTime() {
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

    public final void sendTic(final Tic ticToSend) {
        if (tic != null && tic.getConnectedBindingsSize() > 0) {
            tic.send(ticToSend.toString(), null);
        }
    }

    public final void tic() {
        if (showTic) {
            logInfo("Tic");
        }
        if (tic != null && tic.getConnectedBindingsSize() > 0) {
            Tic nextTic = new Tic(getUTCTime(), zip,
                    getTimezoneOffset(), status, TimeCommand.CARRYON);
            tic.send(nextTic.toString(), null);
        }
    }

    public final void checkSunsetSunrise() {
        SunsetSunrise sunsetSunrise = new SunsetSunrise(52.041404, -0.72878,
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
            SunsetSunrise nextSS = new SunsetSunrise(52.041404, -0.72878,
                    new Date(getUTCTime() + DAY), getTimezoneOffset());
            nextCheck = (nextSS.getSunrise().getTime() - getUTCTime());
        }
        stpe.schedule(this::checkSunsetSunrise,
                nextCheck / zip, TimeUnit.MILLISECONDS);
    }

    public final boolean pauseTime() {
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

    public final boolean resumeTime() {
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

    public final boolean stopTime() {
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

    public final long getUTCTime() {
        if (startTS != -1) {
            if (status == TimeStatus.RUNNING) {
                return startTS + (System.currentTimeMillis() - initTS - idleDuration) * zip;
            } else if (status == TimeStatus.IDLE || status == TimeStatus.STOPPED) {
                return startTS + (pauseTS - initTS - idleDuration) * zip;
            }
            return startTS;
        }
        return System.currentTimeMillis();
    }

    public final int getTimezoneOffset() {
        return (int) (timezone.getOffset(getUTCTime()) / HOUR);
    }

    public final Object setProperties(final JsonObject properties) {
        if (properties.get("timezone") != null) {
            timezone = TimeZone.getTimeZone(properties.get("timezone").asString());
        }

        try {
            long localStart = 0;
            if (properties.get("startDate")!=null) {
                localStart = df.parse(properties.get("startDate").asString()).getTime();
            } else if (properties.get("startTS")!=null) {
                localStart = properties.get("startTS").asLong();
            }

            long start = localStart - timezone.getOffset(localStart);
            int newZip = properties.get("zip").asInt();
            if (newZip == 0) {
                newZip = 1;
            }
            startTS = start;
            zip = newZip;
            long ticFreq = HOUR / zip;
            ticFrequency = ticFreq;

            init();
        } catch (ParseException e) {
            e.printStackTrace();
        }

        return getProperties();
    }

    public final Object getProperties() {
        JsonObject prop = new JsonObject();
        prop.add("startDate", startTS);
        prop.add("zip", zip);
        prop.add("status", status.name());
        return prop;
    }

    @Stop
    public final void stop() {
        stpe.shutdownNow();
    }

    @Override
    public void modelUpdated() {
        if (isFirstModelUpdate()) {
            sendRequest(new Request(getFullId(), getNode() + ".http", getCurrentTime(),
                            "addHandler", new Object[]{"/timekeeper", getFullId(), true}),
                    new ShowIfErrorCallback());
        }
        if (status.equals(Status.UNKNOWN)) {
            init();
        } else {
            sendTic(new Tic(getUTCTime(), zip,
                    getTimezoneOffset(), status, TimeCommand.CARRYON));
        }
        super.modelUpdated();
    }

    private  void initExecutor() {
        stpe = new ScheduledThreadPoolExecutor(1, r -> {
            return new Thread(r, getFullId() + "-timekeeper-pool");
        });
    }

}



