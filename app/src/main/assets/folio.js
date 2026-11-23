// Bridge between the Folio Android reader and foliate-js. Android -> JS: window.folio.*  JS -> Android: window.Android.*
import './foliate/view.js'

const A = window.Android
let view = null
let style = JSON.parse(A.getStyle())

const lang = x => !x ? '' : typeof x === 'string' ? x : (x[Object.keys(x)[0]] ?? '')
const people = x => (Array.isArray(x) ? x : [x]).filter(Boolean).map(p => typeof p === 'string' ? p : lang(p.name)).join(', ')

const fonts = {
    serif: '"Noto Serif", Georgia, "Times New Roman", serif',
    sans: 'Roboto, "Noto Sans", "Helvetica Neue", sans-serif',
    publisher: '',
}

const css = s => {
    const font = fonts[s.fontFamily] ?? ''
    // Book colours are forced only for non-light themes, so light mode keeps the publisher's styling.
    const force = s.theme !== 'light'
    return `
    @namespace epub "http://www.idpf.org/2007/ops";
    html { color-scheme: ${s.dark ? 'dark' : 'light'}; font-size: ${s.fontScale}% !important; }
    html, body { color: ${s.fg} !important; background: transparent !important; }
    ${font ? `body, body * { font-family: ${font} !important; }` : ''}
    ${force ? `body * { color: inherit !important; background-color: transparent !important; border-color: currentColor !important; }` : ''}
    a:link, a:visited { color: ${s.link} !important; }
    p, li, blockquote, dd, div {
        line-height: ${s.lineHeight} !important;
        text-align: ${s.justify ? 'justify' : 'start'};
        -webkit-hyphens: auto; hyphens: auto;
        widows: 2; orphans: 2;
    }
    [align="left"] { text-align: left; } [align="right"] { text-align: right; }
    [align="center"] { text-align: center; }
    pre { white-space: pre-wrap !important; }
    img, svg { max-width: 100%; height: auto; }
    ${s.dark ? 'img { filter: brightness(.9); }' : ''}
    aside[epub|type~="footnote"], aside[epub|type~="endnote"], aside[epub|type~="note"], aside[epub|type~="rearnote"] { display: none; }
`
}

const applyStyle = () => {
    document.documentElement.style.setProperty('--bg', style.bg)
    const r = view?.renderer
    if (!r) return
    const side = Math.max(8, style.margin)
    r.setAttribute('flow', style.paginated ? 'paginated' : 'scrolled')
    r.setAttribute('gap', `${Math.min(30, Math.round(200 * side / Math.max(320, innerWidth)))}%`)
    r.setAttribute('margin', '44px')
    r.setAttribute('max-inline-size', '720px')
    r.setAttribute('max-column-count', '2')
    r.setStyles?.(css(style))
}

const tap = (doc, e) => {
    if (e.defaultPrevented || e.target.closest?.('a[href]')) return
    if (doc.getSelection?.()?.toString()) return
    const frame = doc.defaultView?.frameElement
    const x = (frame ? frame.getBoundingClientRect().left : 0) + e.clientX
    const w = innerWidth
    A.onTap(x < w * 0.3 ? 'left' : x > w * 0.7 ? 'right' : 'center')
}

const flatToc = (items, depth = 0, out = []) => {
    for (const t of items ?? []) {
        out.push({ label: (t.label ?? '').trim(), href: t.href ?? '', depth })
        flatToc(t.subitems, depth + 1, out)
    }
    return out
}

const open = async () => {
    const init = JSON.parse(A.getInit())
    const res = await fetch('/book/' + encodeURIComponent(init.name))
    if (!res.ok) throw new Error(`Could not read the file (${res.status})`)
    const file = new File([await res.blob()], init.name, { type: init.type })

    view = document.createElement('foliate-view')
    document.body.append(view)
    view.addEventListener('relocate', e => {
        const d = e.detail
        A.onRelocate(d.cfi ?? '', d.fraction ?? 0, d.tocItem?.label?.trim() ?? '')
    })
    view.addEventListener('load', e => {
        const { doc } = e.detail
        doc.addEventListener('click', ev => tap(doc, ev))
    })
    view.addEventListener('external-link', e => {
        e.preventDefault()
        A.openLink(e.detail.href_ ?? '')
    })
    await view.open(file)
    const { book } = view
    book.transformTarget?.addEventListener('data', ({ detail }) => {
        detail.data = Promise.resolve(detail.data).catch(() => '')
    })
    applyStyle()
    // Taps outside the book iframe (margins) still toggle chrome / turn pages.
    document.addEventListener('click', e => tap(document, e))
    A.onReady(JSON.stringify({
        title: lang(book.metadata?.title),
        author: people(book.metadata?.author),
        toc: flatToc(book.toc),
        rtl: book.dir === 'rtl',
    }))
    await view.init({ lastLocation: init.cfi || null, showTextStart: !init.cfi })
}

window.folio = {
    next: () => view?.next(),
    prev: () => view?.prev(),
    left: () => view?.goLeft(),
    right: () => view?.goRight(),
    goTo: href => view?.goTo(href),
    goToFraction: f => view?.goToFraction(f),
    setStyle: s => { style = s; applyStyle() },
}

window.addEventListener('unhandledrejection', e => A.onError(String(e.reason?.message ?? e.reason)))
window.addEventListener('error', e => A.onError(String(e.message)))
applyStyle()
open().catch(e => A.onError(String(e?.message ?? e)))
