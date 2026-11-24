import { useEffect, useRef, useState } from "react";
import {
  ArrowsPointingOutIcon,
  XMarkIcon,
  ClipboardDocumentIcon,
  ArrowDownTrayIcon,
  ShieldCheckIcon,
} from "@heroicons/react/24/outline";
import { useDateFormat } from "~/hooks/useFormatters";
import { useI18n } from "~/contexts/I18nContext";
import { useToastContext } from "~/contexts/ToastContext";

export interface HttpBodyCapture {
  state: "captured" | "empty" | "truncated" | "not_captured";
  contentType?: string;
  capturedBytes?: number;
  reason?: string;
  body?: unknown;
}
export interface HttpAuditDetail {
  auditPid: string;
  requestId: string;
  method: string;
  path: string;
  status: number;
  durationMs: number;
  occurredAt: string;
  remoteAddress?: string;
  captureState: "captured" | "not_captured";
  httpDetails?: {
    version: number;
    limitBytes: number;
    queryState: string;
    requestHeaders: Record<string, string>;
    responseHeaders: Record<string, string>;
    query: Record<string, unknown>;
    request: HttpBodyCapture;
    response: HttpBodyCapture;
  } | null;
}
export function useAuditText() {
  const { t } = useI18n();
  return (key: string, fallback: string) =>
    t(`openPlatform.logs.${key}`, undefined, fallback);
}
const button =
  "rounded-control border-border-strong bg-panel text-text-2 inline-flex min-h-8 items-center justify-center gap-1.5 border px-2.5 text-xs hover:bg-hover focus-visible:outline-accent disabled:opacity-50";

/** Shared diagnostic viewer. All data must already be redacted and authorized by the server. */
export function HttpAuditInspector({
  detail,
  onClose,
  expanded,
  onExpand,
  applicationName,
  environment,
}: {
  detail: HttpAuditDetail;
  onClose: () => void;
  expanded: boolean;
  onExpand: () => void;
  applicationName?: string;
  environment?: string;
}) {
  const text = useAuditText();
  const { locale } = useI18n();
  const { showToast } = useToastContext();
  const [tab, setTab] = useState("messages");
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    heading.current?.focus();
  }, [detail.auditPid]);
  const { formatDate } = useDateFormat();
  const data = detail.httpDetails;
  const occurred = formatDate(detail.occurredAt, {
    dateStyle: "short",
    timeStyle: "medium",
  });
  const copy = async (value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      showToast(text("copied", "Redacted content copied"), "success");
    } catch {
      showToast(
        text(
          "copyFailed",
          "Clipboard unavailable. Download the redacted record instead.",
        ),
        "error",
      );
    }
  };
  const curl = () => {
    const body =
      data?.request.state === "captured"
        ? JSON.stringify(data.request.body)
        : null;
    const shellQuote = (value: string) => `'${value.replaceAll("'", "'\\''")}'`;
    // A template only: it deliberately contains neither a live origin nor credentials.
    return `curl -X ${detail.method} ${shellQuote("https://api.example.com" + detail.path)} \\\n  -H 'Authorization: Bearer <YOUR_ACCESS_TOKEN>'${body ? " \\\n  -H 'Content-Type: application/json' \\\n  --data-raw " + shellQuote(body) : ""}`;
  };
  const download = () => {
    const content = {
      ...detail,
      exportedAt: new Date().toISOString(),
      redacted: true,
    };
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(content, null, 2)], {
        type: "application/json",
      }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `api-call-${detail.auditPid}-redacted.json`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast(text("downloaded", "Redacted record downloaded"), "success");
  };
  const stateText = (body: HttpBodyCapture) =>
    body.state === "empty"
      ? text("emptyBody", "This call has no body.")
      : body.state === "truncated"
        ? text(
            "truncatedBody",
            "Body exceeds the 64 KB capture limit. No partial body is stored because it cannot be safely redacted.",
          )
        : body.reason === "invalid_json"
          ? text("invalidJson", "Invalid JSON was not stored.")
          : body.reason === "request_body_not_read"
            ? text(
                "unreadBody",
                "The request was rejected before its body was read. No body was captured.",
              )
            : body.reason === "incomplete_response"
              ? text(
                  "incompleteResponse",
                  "The response did not complete. No response body was captured.",
                )
              : text(
                  "unsupportedBody",
                  "Body format is not supported for safe capture. Only JSON is captured.",
                );
  function headers(title: string, values: Record<string, unknown>, id: string) {
    return (
      <details className="border-border border-b" data-testid={id}>
        <summary className="text-text-2 cursor-pointer px-3 py-2 text-xs">
          {title}
          <span className="text-text-3 ms-2">{Object.keys(values).length}</span>
        </summary>
        <dl className="px-3 pb-2 text-xs">
          {Object.entries(values).map(([key, value]) => (
            <div
              key={key}
              className="grid min-w-0 grid-cols-[minmax(90px,0.7fr)_minmax(0,1fr)] gap-2 py-1"
            >
              <dt className="text-text-3 break-all">{key}</dt>
              <dd className="text-text-2 min-w-0 break-all font-mono">
                {typeof value === "string" ? value : JSON.stringify(value)}
              </dd>
            </div>
          ))}
        </dl>
      </details>
    );
  }
  function payload(request: boolean) {
    if (!data) return null;
    const body = request ? data.request : data.response;
    const bodyString =
      body.state === "captured" ? JSON.stringify(body.body, null, 2) : "";
    return (
      <section
        className="rounded-card border-border-strong min-w-0 overflow-hidden border"
        data-testid={request ? "http-request" : "http-response"}
      >
        <header className="border-border bg-subtle flex min-h-11 flex-wrap items-center justify-between gap-2 border-b px-3 py-2 text-xs">
          <b>
            {request
              ? text("request", "Request")
              : text("response", "Response")}
          </b>
          {request ? (
            <button
              type="button"
              className="text-accent text-xs disabled:opacity-50"
              onClick={() => void copy(curl())}
              disabled={body.state !== "captured" && body.state !== "empty"}
              data-testid="copy-curl"
            >
              {text("copyCurl", "Copy cURL template")}
            </button>
          ) : (
            <span className="text-text-3">HTTP {detail.status}</span>
          )}
        </header>
        {headers(
          request
            ? text("requestHeaders", "Request headers")
            : text("responseHeaders", "Response headers"),
          request ? data.requestHeaders : data.responseHeaders,
          request ? "request-headers" : "response-headers",
        )}
        {request && (
          <>
            {headers(
              text("query", "Query parameters"),
              data.query,
              "request-query",
            )}
            {data.queryState === "truncated" && (
              <p className="text-amber-700 px-3 py-2 text-xs">
                {text(
                  "queryTruncated",
                  "Query parameters exceeded the capture limit and were not stored.",
                )}
              </p>
            )}
          </>
        )}
        <details open>
          <summary className="text-text-2 cursor-pointer px-3 py-2 text-xs">
            {request
              ? text("requestBody", "Request body")
              : text("responseBody", "Response body")}
          </summary>
          {body.state === "captured" ? (
            <div className="border-border bg-subtle border-t">
              <div className="text-text-3 flex items-center justify-between px-3 py-2 text-xs">
                <span>JSON · UTF-8</span>
                <button
                  type="button"
                  aria-label={
                    request
                      ? text("copyRequest", "Copy request body")
                      : text("copyResponse", "Copy response body")
                  }
                  onClick={() => void copy(bodyString)}
                >
                  <ClipboardDocumentIcon className="h-4 w-4" />
                </button>
              </div>
              <pre className="text-text-2 max-h-96 overflow-auto px-3 pb-4 text-xs leading-6 whitespace-pre-wrap break-all">
                <code>{bodyString}</code>
              </pre>
            </div>
          ) : (
            <p
              className={`px-3 pb-4 text-xs leading-6 ${body.state === "truncated" ? "text-amber-700" : "text-text-3"}`}
              data-testid={`${request ? "request" : "response"}-body-state`}
            >
              {stateText(body)}
            </p>
          )}
        </details>
      </section>
    );
  }
  return (
    <aside
      className="border-border-strong bg-panel flex min-w-0 flex-col border-s"
      aria-label={text("callDetail", "Call details")}
      data-testid="http-audit-inspector"
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.stopPropagation();
          onClose();
        }
      }}
    >
      <header className="flex items-start justify-between gap-2 px-5 pt-4 pb-3">
        <div>
          <p className="text-text-3 mb-1 text-xs">
            {text("callDetail", "Call details")}
          </p>
          <h2
            tabIndex={-1}
            ref={heading}
            className="text-text text-base font-semibold outline-none"
          >
            {detail.method} · {text("callResult", "API call result")}
          </h2>
        </div>
        <div className="flex gap-1">
          <button
            type="button"
            className={button}
            onClick={onExpand}
            aria-label={
              expanded
                ? text("restore", "Restore detail width")
                : text("expand", "Expand details")
            }
          >
            <ArrowsPointingOutIcon className="h-4 w-4" />
          </button>
          <button
            type="button"
            className={button}
            onClick={onClose}
            aria-label={text("close", "Close details")}
          >
            <XMarkIcon className="h-4 w-4" />
          </button>
        </div>
      </header>
      <div className="min-w-0 px-5 pb-4">
        <div className="text-text-2 mb-3 flex min-w-0 items-start gap-2">
          <span className="bg-accent-weak text-accent rounded-control px-1.5 py-0.5 font-mono text-xs">
            {detail.method}
          </span>
          <code className="min-w-0 text-xs leading-6 break-all">
            {detail.path}
          </code>
        </div>
        <div className="text-text-3 mb-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
          <HttpStatus status={detail.status} />
          <span>{detail.durationMs} ms</span>
          <span>{occurred}</span>
        </div>
        <div className="text-text-3 mb-3 flex flex-wrap items-center gap-2 text-xs">
          <span>{text("requestId", "Request ID")}</span>
          <code className="text-text-2 break-all">{detail.requestId}</code>
          <button
            type="button"
            onClick={() => void copy(detail.requestId)}
            aria-label={text("copyId", "Copy request ID")}
          >
            <ClipboardDocumentIcon className="h-4 w-4" />
          </button>
        </div>
        {detail.status === 202 && (
          <p className="rounded-control bg-accent-weak text-accent mb-3 px-3 py-2 text-xs leading-6">
            {text(
              "acceptedHelp",
              "Accepted for processing. Query the original event ID for the final business result.",
            )}
          </p>
        )}
        {detail.status >= 400 && (
          <p className="rounded-control mb-3 bg-red-50 px-3 py-2 text-xs leading-6 text-red-700">
            {text(
              "failureHelp",
              "The call failed. Review the response body and headers before the caller submits another request.",
            )}
          </p>
        )}
        <div
          role="tablist"
          aria-label={text("detailSections", "Call detail sections")}
          className="border-border mb-3 flex gap-6 border-b"
        >
          {(["messages", "execution"] as const).map((value) => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={tab === value}
              tabIndex={tab === value ? 0 : -1}
              onClick={() => setTab(value)}
              onKeyDown={(event) => {
                if (["ArrowRight", "ArrowLeft"].includes(event.key)) {
                  event.preventDefault();
                  setTab(value === "messages" ? "execution" : "messages");
                  (
                    event.currentTarget.parentElement?.querySelector(
                      `[data-tab="${value === "messages" ? "execution" : "messages"}"]`,
                    ) as HTMLElement
                  )?.focus();
                }
              }}
              data-tab={value}
              className={`pb-2 text-xs ${tab === value ? "border-accent text-accent border-b-2 font-medium" : "text-text-2"}`}
            >
              {value === "messages"
                ? text("messages", "Request and response")
                : text("execution", "Execution info")}
            </button>
          ))}
        </div>
        {tab === "messages" ? (
          data ? (
            <>
              <p className="text-text-3 mb-3 flex items-center gap-1.5 text-xs">
                <ShieldCheckIcon className="h-4 w-4 shrink-0" />
                {text(
                  "redaction",
                  "Sensitive fields are redacted. Copies and downloads use the same redacted content.",
                )}
              </p>
              <div
                className={`grid min-w-0 gap-3 ${expanded ? "xl:grid-cols-2" : "min-[1600px]:grid-cols-2"}`}
              >
                {payload(true)}
                {payload(false)}
              </div>
            </>
          ) : (
            <div className="rounded-card border-border-strong border border-dashed px-5 py-12 text-center">
              <h3 className="text-text mb-2 text-sm font-medium">
                {text(
                  "historical",
                  "This historical record has no captured HTTP content",
                )}
              </h3>
              <p className="text-text-3 text-xs leading-6">
                {text(
                  "historicalHelp",
                  "Only the call summary was recorded. Enabling capture cannot recover old request or response bodies.",
                )}
              </p>
            </div>
          )
        ) : (
          <dl className="grid grid-cols-2 gap-5 py-3 text-xs">
            {[
              [text("app", "Application"), applicationName],
              [text("environment", "Environment"), environment],
              [text("occurredAt", "Call time"), occurred],
              [text("duration", "Server duration"), `${detail.durationMs} ms`],
              [text("remote", "Source address"), detail.remoteAddress],
            ].map(([name, value]) => (
              <div key={name}>
                <dt className="text-text-3 mb-2">{name}</dt>
                <dd className="text-text-2 break-all">{value || "—"}</dd>
              </div>
            ))}
          </dl>
        )}
      </div>
      <footer className="border-border mt-auto flex flex-wrap items-center justify-between gap-3 border-t px-5 py-3">
        <span className="text-text-3 text-xs">
          {text("tenantOnly", "Current tenant only")}
        </span>
        <button
          type="button"
          className={button}
          onClick={download}
          disabled={!data}
          data-testid="download-http-audit"
        >
          <ArrowDownTrayIcon className="h-4 w-4" />
          {text("download", "Download redacted record")}
        </button>
      </footer>
    </aside>
  );
}
export function HttpStatus({ status }: { status: number }) {
  const text = useAuditText();
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      <span
        className={`h-1.5 w-1.5 shrink-0 rounded-full ${status >= 400 ? "bg-red-600" : status < 300 ? "bg-green-600" : "bg-amber-600"}`}
      />
      <span className="text-text-2">
        {status}{" "}
        <span className="text-text-3">
          {status === 202
            ? text("accepted", "Accepted")
            : status < 300
              ? text("success", "Success")
              : status === 429
                ? text("limited", "Rate limited")
                : status >= 400
                  ? text("failed", "Failed")
                  : text("redirected", "Redirected")}
        </span>
      </span>
    </span>
  );
}
