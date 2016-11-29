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
import com.eclipsesource.json.JsonValue;
import org.activehome.com.Request;
import org.activehome.service.RequestHandler;
import org.activehome.tools.file.FileHelper;
import org.activehome.tools.file.TypeMime;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
public class TimekeeperRequestHandler implements RequestHandler {

    private final Request request;
    private final Timekeeper service;

    public TimekeeperRequestHandler(Request request, Timekeeper service) {
        this.request = request;
        this.service = service;
    }

    public long getTime() {
        return service.getUTCTime();
    }

    public boolean startTime() {
        return service.startTime();
    }

    public boolean stopTime() {
        return service.stopTime();
    }

    public boolean pauseTime() {
        return service.pauseTime();
    }

    public boolean resumeTime() {
        return service.resumeTime();
    }

    public JsonValue html() {
        JsonObject wrap = new JsonObject();
        wrap.add("name", "timekeeper-view");
        wrap.add("url", service.getId() + "/timekeeper-view.html");
        wrap.add("title", "Active Home Timekeeper");
        wrap.add("description", "Active Home Timekeeper");

        JsonObject json = new JsonObject();
        json.add("wrap", wrap);
        return json;
    }

    public JsonValue file(String str) {
        String content = FileHelper.fileToString(str, getClass().getClassLoader());
        if (str.compareTo("timekeeper-view.html") == 0) content = content.replaceAll("\\$\\{id\\}", service.getId());
        JsonObject json = new JsonObject();
        json.add("content", content);
        json.add("mime", TypeMime.valueOf(str.substring(str.lastIndexOf(".") + 1, str.length())).getDesc());
        return json;
    }

    public Object setProperties(JsonObject properties) {
        return service.setProperties(properties);
    }

    public Object getProperties() {
        return service.getProperties();
    }

    public boolean initTime() {
        return service.init();
    }


}
