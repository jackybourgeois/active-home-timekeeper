package org.activehome.timekeeper;

/*
 * #%L
 * Active Home :: Timekeeper
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
import com.eclipsesource.json.JsonValue;
import org.activehome.service.RequestHandler;
import org.activehome.tools.file.FileHelper;
import org.activehome.tools.file.TypeMime;

/**
 * Methods of the timekeeper accessible from other
 * components through {@code Service#getRequest}.
 *
 * @author Jacky Bourgeois
 */
public class TimekeeperRequestHandler implements RequestHandler {

    /**
     * The service to access.
     */
    private final Timekeeper service;

    /**
     * Constructor, restricted to package access, to be
     * called by {@code Timekeeper} only.
     * @param timekeeperService the {@code Timekeeper} instantiating the handle
     */
    TimekeeperRequestHandler(final Timekeeper timekeeperService) {
        service = timekeeperService;
    }

    /**
     * Get the current UTC time: emulated idle/running or actual.
     *
     * @return UNIX timestamp.
     */
    public final long getTime() {
        return service.getUTCTime();
    }

    /**
     * Switch the status to INITIALIZED and send a Tic
     * which contains the time command INIT.
     *
     * @return true
     */
    public boolean initTime() {
        return service.init();
    }

    /**
     * Start sending Tic at the given time zip and frequency.
     * The first Tic contains the time command START.
     *
     * @return true if the time was initialize before
     */
    public final boolean startTime() {
        return service.startTime();
    }

    /**
     * Stop sending regular Tics with a last one containing
     * the time command STOP.
     *
     * @return true if time was running or idled
     */
    public final boolean stopTime() {
        return service.stopTime();
    }

    /**
     * Stop sending regular Tics with a last one containing
     * the time command PAUSE.
     *
     * @return true if the time was running
     */
    public final boolean pauseTime() {
        return service.pauseTime();
    }

    /**
     * Restart sending regular Tics with the first one containing
     * the time command RESUME.
     *
     * @return true if the time was idled
     */
    public final boolean resumeTime() {
        return service.resumeTime();
    }

    /**
     * @return an String containing a html view of the timekeeper
     */
    public final JsonValue html() {
        JsonObject wrap = new JsonObject();
        wrap.add("name", "timekeeper-view");
        wrap.add("url", service.getId() + "/timekeeper-view.html");
        wrap.add("title", "Active Home Timekeeper");
        wrap.add("description", "Active Home Timekeeper");

        JsonObject json = new JsonObject();
        json.add("wrap", wrap);
        return json;
    }

    /**
     * Serve content file as string.
     *
     * @param fileName the file to serve
     * @return a json containing the file as string
     * in 'content' and the type of the file in 'mime'
     */
    public final JsonValue file(final String fileName) {
        String content = FileHelper.fileToString(
                fileName, getClass().getClassLoader());
        if (fileName.compareTo("timekeeper-view.html") == 0) {
            content = content.replaceAll("\\$\\{id\\}", service.getId());
        }
        JsonObject json = new JsonObject();
        json.add("content", content);
        json.add("mime", TypeMime.valueOf(
                fileName.substring(fileName.lastIndexOf(".") + 1,
                        fileName.length())).getDesc());
        return json;
    }

    /**
     * Set the timekeeper properties.
     * Properties can be
     * - timezone (e.g 'Europe/Amsterdam' or 'UTC')
     * - start (e.g 'yyyy-MM-dd HH:mm:ss', 'actual' or UNIX timestamp)
     * - zip (e.g 1 or 'x1')
     *
     * @param properties properties as Json
     * @return {@code #getProperties} showing the new status of all properties.
     */
    public final JsonObject setProperties(final JsonObject properties) {
        return service.setProperties(properties);
    }

    /**
     * @return return a Json with the start date, zip and status.
     */
    public final JsonObject getProperties() {
        return service.getProperties();
    }

}
