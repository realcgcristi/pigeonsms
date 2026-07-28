const ORIGIN = 'https://pigeonsms-web.pages.dev';

export default {
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const target = new URL(url.pathname + url.search, ORIGIN);
    const upstream = new Request(target.toString(), request);
    upstream.headers.set('host', 'pigeonsms-web.pages.dev');
    const response = await fetch(upstream);
    const headers = new Headers(response.headers);
    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers,
    });
  },
};
