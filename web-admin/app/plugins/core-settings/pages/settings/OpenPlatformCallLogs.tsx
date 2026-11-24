import { useCallback, useEffect, useRef, useState } from "react";
import {
  ArrowPathIcon,
  MagnifyingGlassIcon,
} from "@heroicons/react/24/outline";
import { useDateFormat } from "~/hooks/useFormatters";
import { useI18n } from "~/contexts/I18nContext";
import {
  HttpAuditInspector,
  HttpStatus,
  useAuditText,
  type HttpAuditDetail,
} from "~/ui/smart/diagnostics/HttpAuditInspector";
import {
  OpenPlatformApiError,
  openPlatformApiFetch as apiFetch,
} from "./open-platform-api";

interface AuditSummary {
  auditPid: string;
  requestId: string;
  method: string;
  path: string;
  status: number;
  durationMs: number;
  occurredAt: string;
}
interface Overview {
  windowHours: number;
  totalCalls: number;
  errorCalls: number;
  errorRate: number;
  p95DurationMs: number;
}
const control =
  "rounded-control border-border-strong bg-panel text-text-2 h-8 border px-2.5 text-xs focus-visible:outline-accent";
const pageSize = 25;
export default function OpenPlatformCallLogs({
  installationPid,
  applicationName,
  environment,
  canReadPayload,
  contextPending = false,
}: {
  installationPid: string;
  applicationName: string;
  environment: string;
  canReadPayload: boolean;
  contextPending?: boolean;
}) {
  const text = useAuditText();
  const { locale } = useI18n();
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const [windowHours, setWindowHours] = useState("24");
  const [method, setMethod] = useState("");
  const [statusClass, setStatusClass] = useState("all");
  const [page, setPage] = useState(0);
  const [audits, setAudits] = useState<AuditSummary[]>([]);
  const [overview, setOverview] = useState<Overview | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [selected, setSelected] = useState<AuditSummary | null>(null);
  const [detail, setDetail] = useState<HttpAuditDetail | null>(null);
  const [detailState, setDetailState] = useState<
    "loading" | "ready" | "denied" | "error"
  >("loading");
  const [expanded, setExpanded] = useState(false);
  const [reload, setReload] = useState(0);
  const [detailReload, setDetailReload] = useState(0);
  const { formatDate } = useDateFormat();
  const trigger = useRef<HTMLButtonElement | null>(null);
  useEffect(() => {
    if (keyword === search) return;
    const timer = setTimeout(() => {
      setSearch(keyword);
      setPage(0);
    }, 250);
    return () => clearTimeout(timer);
  }, [keyword, search]);
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(false);
    setSelected(null);
    setDetail(null);
    setExpanded(false);
    const params = new URLSearchParams({
      windowHours,
      limit: String(pageSize + 1),
      offset: String(page * pageSize),
      statusClass,
    });
    if (search.trim()) params.set("keyword", search.trim());
    if (method) params.set("method", method);
    void Promise.all([
      apiFetch<AuditSummary[]>(
        `/api/open-platform/installations/${installationPid}/audits?${params}`,
        { signal: controller.signal },
      ),
      apiFetch<Overview>(
        `/api/open-platform/installations/${installationPid}/overview?windowHours=${windowHours}`,
        { signal: controller.signal },
      ),
    ])
      .then(([rows, metrics]) => {
        if (controller.signal.aborted) return;
        setHasMore(rows.length > pageSize);
        setAudits(rows.slice(0, pageSize));
        setOverview(metrics);
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setError(true);
          setAudits([]);
          setOverview(null);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [installationPid, page, search, method, statusClass, windowHours, reload]);
  useEffect(() => {
    if (!selected) return;
    if (!canReadPayload) {
      setDetailState("denied");
      setDetail(null);
      return;
    }
    const controller = new AbortController();
    setDetailState("loading");
    setDetail(null);
    void apiFetch<HttpAuditDetail>(
      `/api/open-platform/installations/${installationPid}/audits/${selected.auditPid}`,
      { signal: controller.signal },
    )
      .then((value) => {
        if (!controller.signal.aborted) {
          setDetail(value);
          setDetailState("ready");
        }
      })
      .catch((cause) => {
        if (!controller.signal.aborted)
          setDetailState(
            cause instanceof OpenPlatformApiError && cause.status === 403
              ? "denied"
              : "error",
          );
      });
    return () => controller.abort();
  }, [selected, installationPid, canReadPayload, detailReload]);
  const close = useCallback(() => {
    setSelected(null);
    setExpanded(false);
    trigger.current?.focus();
  }, []);
  const reset = () => {
    setKeyword("");
    setSearch("");
    setMethod("");
    setStatusClass("all");
    setWindowHours("24");
    setPage(0);
  };
  const metrics = [
    [
      text("total", "Total calls"),
      overview?.totalCalls?.toLocaleString(locale) ?? "—",
    ],
    [
      text("errorRate", "Error rate"),
      overview ? `${(overview.errorRate * 100).toFixed(2)}%` : "—",
    ],
    [
      text("failureCount", "Failed calls"),
      overview?.errorCalls?.toLocaleString(locale) ?? "—",
    ],
    [
      text("p95", "P95 response time"),
      overview ? `${Math.round(overview.p95DurationMs)} ms` : "—",
    ],
  ];
  return (
    <section
      className="min-w-0 space-y-4"
      data-testid="open-platform-call-logs" inert={contextPending || undefined} aria-busy={contextPending || loading}
    >
      <div
        className="rounded-card border-border bg-panel grid grid-cols-2 gap-y-4 border py-4 lg:grid-cols-4"
        aria-label={text("overview", "Call overview")}
      >
        {metrics.map(([label, value], index) => (
          <div
            key={label}
            className="border-border border-e px-5 last:border-e-0"
          >
            <p className="text-text-2 text-xs">{label}</p>
            {index === 2 ? (
              <button
                type="button"
                className="mt-1 text-2xl font-semibold text-red-600"
                onClick={() => {
                  setStatusClass("error");
                  setPage(0);
                }}
              >
                {value}
              </button>
            ) : (
              <p className="text-text mt-1 text-2xl font-semibold tabular-nums">
                {value}
              </p>
            )}
            <p className="text-text-3 mt-1 text-xs">
              {text(
                "currentWindow",
                "Current application, environment and time window",
              )}
            </p>
          </div>
        ))}
      </div>
      <div className="rounded-card border-border-strong bg-panel min-w-0 overflow-hidden border">
        <div className="border-border flex flex-wrap items-center gap-2 border-b px-4 py-3">
          <div className="relative min-w-48 flex-1">
            <MagnifyingGlassIcon className="text-text-3 absolute top-2 start-2 h-4 w-4" />
            <input
              className={`${control} w-full ps-8`}
              disabled={contextPending} aria-label={text("search", "Search request ID or API path")}
              placeholder={text("search", "Search request ID or API path")}
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
          </div>
          <select
            className={control}
            aria-label={text("timeRange", "Time range")}
            value={windowHours}
            onChange={(event) => {
              setWindowHours(event.target.value);
              setPage(0);
            }}
          >
            <option value="1">{text("lastHour", "Last hour")}</option>
            <option value="24">{text("lastDay", "Last 24 hours")}</option>
            <option value="168">{text("lastWeek", "Last 7 days")}</option>
          </select>
          <select
            className={control}
            aria-label={text("statusFilter", "Call status")}
            value={statusClass}
            onChange={(event) => {
              setStatusClass(event.target.value);
              setPage(0);
            }}
          >
            <option value="all">{text("allStatuses", "All statuses")}</option>
            <option value="error">
              {text("onlyFailures", "Failed calls")}
            </option>
            <option value="success">{text("onlySuccess", "HTTP 2xx")}</option>
          </select>
          <select
            className={control}
            aria-label={text("method", "HTTP method")}
            value={method}
            onChange={(event) => {
              setMethod(event.target.value);
              setPage(0);
            }}
          >
            <option value="">{text("allMethods", "All methods")}</option>
            {["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"].map(
              (value) => (
                <option key={value}>{value}</option>
              ),
            )}
          </select>
          <button
            type="button"
            className="text-accent px-2 text-xs"
            onClick={reset}
          >
            {text("reset", "Reset")}
          </button>
          <button
            type="button"
            className={control}
            onClick={() => setReload((value) => value + 1)}
            aria-label={text("refresh", "Refresh logs")}
          >
            <ArrowPathIcon
              className={`h-4 w-4 ${loading ? "animate-spin" : ""}`}
            />
          </button>
        </div>
        <div
          className={`grid min-w-0 ${selected && !expanded ? "xl:grid-cols-[minmax(340px,0.9fr)_minmax(430px,1.1fr)]" : "grid-cols-1"}`}
        >
          {!expanded && (
            <div className={`min-w-0 ${selected ? "hidden xl:block" : ""}`}>
              <div className="border-border text-text-3 flex items-center justify-between border-b px-4 py-3 text-xs">
                <b className="text-text">{text("records", "Call records")}</b>
                <span>{text("newest", "Newest first")}</span>
              </div>
              {loading ? (
                <div
                  className="space-y-3 p-4"
                  role="status"
                  aria-label={text("loading", "Loading logs")}
                >
                  {[1, 2, 3, 4].map((i) => (
                    <div
                      key={i}
                      className="bg-hover h-14 animate-pulse rounded"
                    />
                  ))}
                </div>
              ) : error ? (
                <div className="text-text-2 px-5 py-14 text-center">
                  <p className="mb-2 font-medium">
                    {text("loadFailed", "Unable to load logs")}
                  </p>
                  <p className="text-text-3 mb-4 text-xs">
                    {text(
                      "loadFailedHelp",
                      "Filters are preserved. Check the connection and retry.",
                    )}
                  </p>
                  <button
                    type="button"
                    className={control}
                    onClick={() => setReload((value) => value + 1)}
                  >
                    {text("retry", "Retry")}
                  </button>
                </div>
              ) : !audits.length ? (
                <div
                  className="text-text-2 px-5 py-14 text-center"
                  data-testid="call-logs-empty"
                >
                  <MagnifyingGlassIcon className="text-text-3 mx-auto mb-3 h-8 w-8" />
                  <h3 className="text-sm font-medium">
                    {text("noCalls", "No matching calls")}
                  </h3>
                  <p className="text-text-3 mt-2 mb-4 text-xs">
                    {text(
                      "noCallsHelp",
                      "Adjust the request ID, API path, time range or status filters.",
                    )}
                  </p>
                  <button type="button" className={control} onClick={reset}>
                    {text("clearFilters", "Clear filters")}
                  </button>
                </div>
              ) : (
                <div className="max-h-[min(640px,calc(100dvh-24rem))] min-w-0 overflow-auto">
                  <table className="w-full min-w-[520px] table-fixed text-start text-xs xl:min-w-0"><colgroup><col className="w-[34%]"/><col className="w-[34%]"/><col className="w-[20%]"/><col className="w-[12%]"/></colgroup>
                    <thead className="bg-subtle text-text-3">
                      <tr>
                        {[
                          text("timeAndId", "Call time / Request ID"),
                          text("api", "API"),
                          text("result", "Result"),
                          text("duration", "Duration"),
                        ].map((label) => (
                          <th
                            key={label}
                            className="px-4 py-2 text-start font-medium"
                          >
                            {label}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {audits.map((audit) => (
                        <tr
                          key={audit.auditPid}
                          className={`border-border border-b last:border-b-0 ${selected?.auditPid === audit.auditPid ? "bg-accent-weak" : "hover:bg-hover"}`}
                          data-testid={`call-audit-${audit.auditPid}`}
                        >
                          <td className="px-4 py-3">
                            <button
                              type="button"
                              className="text-text text-start focus-visible:outline-accent"
                              aria-label={`${text("viewCall", "View call")} ${audit.requestId}`}
                              onClick={(event) => {
                                trigger.current = event.currentTarget;
                                setSelected(audit);
                              }}
                            >
                              <span className="block whitespace-nowrap tabular-nums">
                                {formatDate(audit.occurredAt, {
                                  dateStyle: "short",
                                  timeStyle: "medium",
                                })}
                              </span>
                              <code
                                className="text-text-3 mt-1 block max-w-40 truncate text-[11px]"
                                title={audit.requestId}
                              >
                                {audit.requestId}
                              </code>
                            </button>
                          </td>
                          <td className="min-w-0 px-3 py-3">
                            <span className="text-accent bg-accent-weak rounded-control inline-block px-1.5 py-0.5 font-mono text-[11px]">
                              {audit.method}
                            </span>
                            <code
                              className="text-text-2 mt-1 block truncate text-[11px]"
                              title={audit.path}
                            >
                              {audit.path}
                            </code>
                          </td>
                          <td className="px-4 py-3">
                            <HttpStatus status={audit.status} />
                          </td>
                          <td className="text-text-2 px-2 py-3 text-end text-[11px] whitespace-nowrap tabular-nums">
                            {audit.durationMs} ms
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <div className="text-text-3 flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-xs">
                <span>
                  {loading || error
                    ? "—"
                    : audits.length
                      ? `${page * pageSize + 1}–${page * pageSize + audits.length}`
                      : "0"}{" "}
                  · {text("timeZone", "Times use your account time zone")}
                </span>
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    className={control}
                    disabled={!page || loading || keyword !== search}
                    onClick={() => setPage((value) => value - 1)}
                    aria-label={text("previous", "Previous page")}
                  >
                    ‹
                  </button>
                  <span>{page + 1}</span>
                  <button
                    type="button"
                    className={control}
                    disabled={!hasMore || loading || error || keyword !== search}
                    onClick={() => setPage((value) => value + 1)}
                    aria-label={text("next", "Next page")}
                  >
                    ›
                  </button>
                </div>
              </div>
            </div>
          )}
          {selected &&
            (detailState === "ready" && detail ? (
              <HttpAuditInspector
                className="max-h-[max(480px,calc(100dvh-24rem))]"
                detail={detail}
                onClose={close}
                expanded={expanded}
                onExpand={() => setExpanded((value) => !value)}
                applicationName={applicationName}
                environment={environment}
              />
            ) : (
              <aside
                className="border-border min-w-0 border-s p-5"
                data-testid="http-audit-state"
              >
                <header className="flex justify-between gap-3">
                  <b className="text-text text-sm">
                    {text("callDetail", "Call details")}
                  </b>
                  <button type="button" onClick={close} className={control}>
                    {text("close", "Close details")}
                  </button>
                </header>
                <div className="text-text-3 my-4 flex flex-wrap gap-3 text-xs">
                  <HttpStatus status={selected.status} />
                  <code className="break-all">{selected.requestId}</code>
                  <span>{selected.durationMs} ms</span>
                </div>
                <div
                  className="border-border rounded-card border border-dashed px-4 py-14 text-center"
                  role="status"
                >
                  <h3 className="text-text mb-2 text-sm font-medium">
                    {detailState === "loading"
                      ? text("loadingDetail", "Loading call details")
                      : detailState === "denied"
                        ? text(
                            "payloadDenied",
                            "No permission to view HTTP content",
                          )
                        : text("detailFailed", "Unable to load call details")}
                  </h3>
                  <p className="text-text-3 text-xs leading-6">
                    {detailState === "denied"
                      ? text(
                          "payloadDeniedHelp",
                          "Call summaries remain available. Ask an application owner for maintenance access.",
                        )
                      : detailState === "error"
                        ? text(
                            "detailFailedHelp",
                            "The record may be unavailable. Retry or refresh the call list.",
                          )
                        : text("wait", "Please wait…")}
                  </p>
                  {detailState === "error" && (
                    <button
                      type="button"
                      className={`${control} mt-4`}
                      onClick={() => setDetailReload((value) => value + 1)}
                    >
                      {text("retry", "Retry")}
                    </button>
                  )}
                </div>
              </aside>
            ))}
        </div>
      </div>
      <p className="text-text-3 text-xs leading-6">
        {text(
          "retentionHelp",
          "202 means accepted, not final business success. Historical HTTP content cannot be recovered. Captures follow the existing audit retention policy.",
        )}
      </p>
    </section>
  );
}
