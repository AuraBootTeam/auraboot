import { useState, useRef } from 'react';
import { useNavigate } from 'react-router';
import { useTimezone } from '~/contexts/TimezoneContext';
import { useAuth } from '~/contexts/AuthContext';
import type { ChildFieldMeta } from './subTableTypes';

export function useSubTableState() {
  const navigate = useNavigate();
  const { hasPermission } = useAuth();
  const { timezone, formats } = useTimezone();
  const [rows, setRows] = useState<Record<string, any>[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshCounter, setRefreshCounter] = useState(0);

  // Inline add form state
  const [isAdding, setIsAdding] = useState(false);
  const [newRowData, setNewRowData] = useState<Record<string, any>>({});
  const [addErrors, setAddErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [rowActionLoadingKey, setRowActionLoadingKey] = useState<string | null>(null);

  // Inline edit state
  const [editingRowId, setEditingRowId] = useState<string | null>(null);
  const [editingValues, setEditingValues] = useState<Record<string, any>>({});
  const [editErrors, setEditErrors] = useState<Record<string, string>>({});
  const [editSaving, setEditSaving] = useState(false);
  const [crossRowErrors, setCrossRowErrors] = useState<string[]>([]);
  const originalEditDataRef = useRef<Record<string, any>>({});
  const [childFieldMap, setChildFieldMap] = useState<Map<string, ChildFieldMeta>>(new Map());
  const [columnMetaLoading, setColumnMetaLoading] = useState(false);
  const [referenceDisplayCache, setReferenceDisplayCache] = useState<
    Record<string, Record<string, string>>
  >({});
  return {
    navigate,
    hasPermission,
    timezone,
    formats,
    rows,
    setRows,
    loading,
    setLoading,
    error,
    setError,
    refreshCounter,
    setRefreshCounter,
    isAdding,
    setIsAdding,
    newRowData,
    setNewRowData,
    addErrors,
    setAddErrors,
    saving,
    setSaving,
    deletingId,
    setDeletingId,
    rowActionLoadingKey,
    setRowActionLoadingKey,
    editingRowId,
    setEditingRowId,
    editingValues,
    setEditingValues,
    editErrors,
    setEditErrors,
    editSaving,
    setEditSaving,
    crossRowErrors,
    setCrossRowErrors,
    originalEditDataRef,
    childFieldMap,
    setChildFieldMap,
    columnMetaLoading,
    setColumnMetaLoading,
    referenceDisplayCache,
    setReferenceDisplayCache,
  };
}
