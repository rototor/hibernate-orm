package org.hibernate.sql;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SelectTest {

    @Test
    public static void main(String[] args) {
        assertEquals("mainTable this_", Select.optimizeJoins("mainTable this_", ""));
    }

}