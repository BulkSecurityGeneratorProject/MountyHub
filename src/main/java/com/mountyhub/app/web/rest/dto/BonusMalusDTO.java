package com.mountyhub.app.web.rest.dto;

/**
 * Created by Theo on 3/28/16.
 */
public class BonusMalusDTO {
    private String name;

    private String type;

    private String effect;

    private Integer duration;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public Integer getDuration() {
        return duration;
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
    }
}
