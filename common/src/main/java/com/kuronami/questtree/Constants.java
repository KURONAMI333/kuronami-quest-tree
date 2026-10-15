package com.kuronami.questtree;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Constants {

    public static final String MOD_ID = "kuronami_quest_tree";
    /** Command root: shorter than the mod id, typed by admins and used in command rewards. */
    public static final String COMMAND = "quest_tree";
    public static final String MOD_NAME = "Kuronami's Quest Tree";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Constants() {
    }
}
