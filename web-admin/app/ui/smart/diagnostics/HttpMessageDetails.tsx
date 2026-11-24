import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface HttpBodyCapture {
  state: 'captured' | 'empty' | 'truncated' | 'not_captured';
  body?: unknown;
}

export interface HttpMessages {
  request: HttpBodyCapture;
  response: HttpBodyCapture;
  requestHeaders?: Record<string, string>;
  responseHeaders?: Record<string, string>;
  query?: Record<string, unknown>;
}

/** Legacy delivery records already contain text; absence does not prove an empty HTTP body. */
export function storedHttpBody(value?: string | null): HttpBodyCapture {
  if (value == null) return { state: 'not_captured' };
  if (value === '') return { state: 'empty' };
  try {
    return { state: 'captured', body: JSON.parse(value) };
  } catch {
    return { state: 'captured', body: value };
  }
}

/** One inline message view for existing inbound audit and outbound delivery details. */
export function HttpMessageDetails({
  request,
  response,
  requestHeaders,
  responseHeaders,
  query,
}: HttpMessages) {
  const { locale, t } = useI18n();
  const label = (zh: string, en: string) => getLocalizedText({ 'zh-CN': zh, en }, locale, t);
  const content = (body: HttpBodyCapture) => {
    if (body.state === 'captured') {
      return (
        <pre className="max-h-64 overflow-auto rounded bg-gray-50 p-3 font-mono text-xs break-all whitespace-pre-wrap text-gray-700 dark:bg-gray-950 dark:text-gray-200">
          {typeof body.body === 'string' ? body.body : JSON.stringify(body.body, null, 2)}
        </pre>
      );
    }
    return (
      <p className="rounded bg-gray-50 p-3 text-xs text-gray-500 dark:bg-gray-950">
        {body.state === 'empty'
          ? label('本次调用无正文', 'This call has no body')
          : body.state === 'truncated'
            ? label(
                '正文超过采集上限，未保存不完整内容',
                'Body exceeds the capture limit; partial content was not stored',
              )
            : label(
                '未采集正文，无法查看历史内容',
                'Body was not captured; historical content is unavailable',
              )}
      </p>
    );
  };
  return (
    <div className="min-w-0 space-y-3" data-testid="http-message-details">
      <div className="grid min-w-0 gap-3 xl:grid-cols-2">
        {[
          [label('请求正文', 'Request body'), request],
          [label('响应正文', 'Response body'), response],
        ].map(([title, body]) => (
          <section key={title as string} className="min-w-0 space-y-1" aria-label={title as string}>
            <h5 className="text-xs font-medium text-gray-600 dark:text-gray-300">
              {title as string}
            </h5>
            {content(body as HttpBodyCapture)}
          </section>
        ))}
      </div>
      {(requestHeaders || responseHeaders || query) && (
        <details className="text-xs text-gray-500">
          <summary className="cursor-pointer py-1">
            {label('请求参数与报文头', 'Query parameters and headers')}
          </summary>
          <div className="mt-2 space-y-3">
            {[
              [label('请求参数', 'Query parameters'), query],
              [label('请求头', 'Request headers'), requestHeaders],
              [label('响应头', 'Response headers'), responseHeaders],
            ].map(
              ([title, values]) =>
                values && (
                  <section key={title as string} className="min-w-0 space-y-1">
                    <h5>{title as string}</h5>
                    <pre className="max-h-40 overflow-auto rounded bg-gray-50 p-2 break-all whitespace-pre-wrap dark:bg-gray-950">
                      {JSON.stringify(values, null, 2)}
                    </pre>
                  </section>
                ),
            )}
          </div>
        </details>
      )}
    </div>
  );
}
