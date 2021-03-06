package org.aion.db.impl.mockdb;

import static org.aion.db.impl.DatabaseFactory.Props;

import java.util.Properties;
import org.aion.db.impl.ByteArrayKeyValueDatabase;
import org.aion.db.impl.IDriver;
import org.slf4j.Logger;

/**
 * Mock implementation of a key value database using a ConcurrentHashMap as our underlying
 * implementation, mostly for testing, when the Driver API interface is create, use this class as a
 * first mock implementation
 *
 * @author yao
 */
public class MockDBDriver implements IDriver {

    private static final int MAJOR_VERSION = 1;
    private static final int MINOR_VERSION = 0;

    /** @inheritDoc */
    @Override
    public ByteArrayKeyValueDatabase connect(Properties info, Logger log) {

        String dbType = info.getProperty(Props.DB_TYPE);
        String dbName = info.getProperty(Props.DB_NAME);

        if (!dbType.equals(this.getClass().getName())) {
            log.error("Invalid dbType provided: {}", dbType);
            return null;
        }

        return new MockDB(dbName, log);
    }

    /** @inheritDoc */
    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    /** @inheritDoc */
    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }
}
