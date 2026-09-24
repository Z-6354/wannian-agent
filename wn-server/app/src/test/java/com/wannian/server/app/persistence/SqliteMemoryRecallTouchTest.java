package com.wannian.server.app.persistence;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SqliteMemoryRecallTouchTest {

    @Test
    void updatesDeduplicatedIdsInBoundedBatches() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        SqliteMemoryRecallTouch touch = new SqliteMemoryRecallTouch(dataSource);
        List<String> ids = IntStream.range(0, 901).mapToObj(i -> "memory-" + i).toList();
        touch.touchRecalled(ids, Instant.parse("2026-09-24T00:00:00Z"));

        verify(connection, times(2)).prepareStatement(anyString());
        verify(statement, times(ids.size() + 2)).setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(statement, times(2)).executeUpdate();
    }

    @Test
    void ignoresBlankAndDuplicateIds() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        new SqliteMemoryRecallTouch(dataSource)
                .touchRecalled(Arrays.asList("  ", null, "a", " a "), Instant.EPOCH);

        verify(dataSource).getConnection();
        verify(connection).prepareStatement(anyString());
        verify(statement, times(2)).setString(org.mockito.ArgumentMatchers.anyInt(), anyString());
        verify(statement).executeUpdate();
    }
}
