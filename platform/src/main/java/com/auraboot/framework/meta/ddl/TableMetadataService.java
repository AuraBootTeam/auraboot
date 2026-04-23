package com.auraboot.framework.meta.ddl;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class TableMetadataService {

    private final DataSource dataSource;
    private final DdlDialectProvider ddlDialectProvider;

    public TableMetadataService(DataSource dataSource, DdlDialectProvider ddlDialectProvider) {
        this.dataSource = dataSource;
        this.ddlDialectProvider = ddlDialectProvider;
    }

    public boolean tableExists(String tableName) {
        try (Connection connection = dataSource.getConnection()) {
            return ddlDialectProvider.getDialect().tableExists(connection, tableName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check table existence: " + tableName, e);
        }
    }

    public boolean columnExists(String tableName, String columnName) {
        try (Connection connection = dataSource.getConnection()) {
            return ddlDialectProvider.getDialect().columnExists(connection, tableName, columnName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check column existence: " + tableName + "." + columnName, e);
        }
    }

    public String getColumnTypeDefinition(String tableName, String columnName) {
        try (Connection connection = dataSource.getConnection()) {
            return ddlDialectProvider.getDialect().getColumnTypeDefinition(connection, tableName, columnName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check column type: " + tableName + "." + columnName, e);
        }
    }

    public boolean isColumnNullable(String tableName, String columnName) {
        try (Connection connection = dataSource.getConnection()) {
            return ddlDialectProvider.getDialect().isColumnNullable(connection, tableName, columnName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check column nullability: " + tableName + "." + columnName, e);
        }
    }

    public boolean indexExists(String tableName, String indexName) {
        try (Connection connection = dataSource.getConnection()) {
            return ddlDialectProvider.getDialect().indexExists(connection, tableName, indexName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check index existence: " + tableName + "." + indexName, e);
        }
    }
}
