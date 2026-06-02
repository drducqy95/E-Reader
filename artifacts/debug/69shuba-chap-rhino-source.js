var console = { log: function(message) { ConsoleHost.log(message); } };
var Response = {
  success: function(data, next) { return { data: data, next: next }; },
  error: function(message) { return { error: String(message) }; }
};
function __wrapElement(element) {
  if (!element) return null;
  var api = {
    select: function(selector) { return __wrapElements(element.select(String(selector))); },
    attr: function(name, value) {
      if (arguments.length > 1) {
        element.attr(String(name), String(value));
        return api;
      }
      return String(element.attr(String(name)));
    },
    hasAttr: function(name) { return element.hasAttr(String(name)); },
    text: function(value) {
      if (arguments.length > 0) {
        element.text(String(value));
        return api;
      }
      return String(element.text());
    },
    html: function(value) {
      if (arguments.length > 0) {
        element.html(String(value));
        return api;
      }
      return String(element.html());
    },
    outerHtml: function() { return String(element.outerHtml()); },
    remove: function() {
      element.remove();
      return api;
    }
  };
  return api;
}
function __wrapElements(elements) {
  var api = {
    length: elements.size(),
    size: function() { return elements.size(); },
    get: function(index) { return __wrapElement(elements.get(Number(index))); },
    first: function() { return __wrapElement(elements.first()); },
    last: function() { return __wrapElement(elements.last()); },
    select: function(selector) { return __wrapElements(elements.select(String(selector))); },
    attr: function(name, value) {
      if (arguments.length > 1) {
        elements.attr(String(name), String(value));
        return api;
      }
      return String(elements.attr(String(name)));
    },
    text: function(value) {
      if (arguments.length > 0) {
        elements.text(String(value));
        return api;
      }
      return String(elements.text());
    },
    html: function(value) {
      if (arguments.length > 0) {
        elements.html(String(value));
        return api;
      }
      return String(elements.html());
    },
    remove: function() {
      elements.remove();
      return api;
    },
    forEach: function(callback) {
      for (var index = 0; index < elements.size(); index++) {
        callback(__wrapElement(elements.get(index)), index, api);
      }
    }
  };
  return api;
}
function __wrapRequest(request) {
  var api = {
    method: function(value) {
      request.method(String(value));
      return api;
    },
    header: function(name, value) {
      request.header(String(name), String(value));
      return api;
    },
    headers: function(values) {
      request.headers(values);
      return api;
    },
    param: function(name, value) {
      request.param(String(name), String(value));
      return api;
    },
    params: function(values) {
      request.params(values);
      return api;
    },
    status: function() { return request.status(); },
    html: function(charset) {
      var document = request.html(charset ? String(charset) : null);
      return document ? __wrapElement(document) : null;
    },
    text: function() { return String(request.string()); },
    string: function() { return String(request.string()); },
    json: function() { return JSON.parse(String(request.string())); }
  };
  return api;
}
var Http = {
  get: function(url) { return __wrapRequest(HttpHost.get(String(url))); },
  post: function(url) { return __wrapRequest(HttpHost.post(String(url))); }
};
var Html = {
  parse: function(html) { return __wrapElement(HtmlHost.parse(String(html))); }
};
function sleep(milliseconds) {
  RuntimeHost.sleep(Math.max(0, Math.min(5000, Number(milliseconds) || 0)));
}
function fetch(url, options) {
  var request = options && String(options.method || 'GET').toUpperCase() === 'POST'
    ? Http.post(String(url)) : Http.get(String(url));
  if (options && options.headers) {
    Object.keys(options.headers).forEach(function(key) {
      request.header(String(key), String(options.headers[key]));
    });
  }
  if (options && options.body) {
    Object.keys(options.body).forEach(function(key) {
      request.param(String(key), String(options.body[key]));
    });
  }
  if (options && options.queries) {
    Object.keys(options.queries).forEach(function(key) {
      request.param(String(key), String(options.queries[key]));
    });
  }
  var status = request.status();
  return {
    ok: status >= 200 && status < 300,
    status: status,
    html: function(charset) {
      return request.html(charset ? String(charset) : null);
    },
    text: function() { return request.string(); },
    json: function() { return request.json(); }
  };
}
var Web = {
  render: function(url, script) {
    return WebViewHost.render(String(url), script ? String(script) : 'document.documentElement.outerHTML');
  }
};
var Engine = {
  newBrowser: function() {
    return {
      launch: function(url) { return BrowserHost.launch(String(url)); },
      close: function() { BrowserHost.close(); }
    };
  }
};
var BASE_URL = "https://www.69shuba.com";  // Æ¯u tiÃªn .com
var CDN_URL = "https://static.69shuba.com";  // cáº­p nháº­t theo domain áº£nh má»›i

/* if (typeof CONFIG_URL !== "undefined") {
    if (CONFIG_URL.indexOf("69shuba.cx") !== -1) {
        BASE_URL = "https://www.69shuba.cx";
        CDN_URL = "https://static.69shuba.cx";
    } else if (CONFIG_URL.indexOf("69shuba.com") === -1) {
        // fallback náº¿u khÃ´ng chá»©a .cx hay .com
        BASE_URL = "https://www.69shuba.com";
        CDN_URL = "https://static.69shuba.com";
    }
} */


// https://stackoverflow.com/a/4673436
if (!String.format) {
    String.format = function(format) {
        var args = Array.prototype.slice.call(arguments, 1);
        return format.replace(/{(\d+)}/g, function(match, number) {
            return typeof args[number] != 'undefined' ?
                args[number] :
                match;
        });
    };
}

// https://stackoverflow.com/a/18234317
String.prototype.formatUnicorn = String.prototype.formatUnicorn ||
function () {
    "use strict";
    var str = this.toString();
    if (arguments.length) {
        var t = typeof arguments[0];
        var key;
        var args = ("string" === t || "number" === t) ?
            Array.prototype.slice.call(arguments)
            : arguments[0];

        for (key in args) {
            str = str.replace(new RegExp("\\{" + key + "\\}", "gi"), args[key]);
        }
    }

    return str;
};

String.prototype.append = function(w) {
    if (this.endsWith(w)) return this;
    return this + w;
}

String.prototype.prepend = function(w) {
    if (this.startsWith(w)) return this;
    return w + this;
}

String.prototype.rtrim = function(s) {
    if (s == undefined) s = '\\s';
    return this.replace(new RegExp("[" + s + "]*$"), '');
}

String.prototype.ltrim = function(s) {
    if (s == undefined) s = '\\s';
    return this.replace(new RegExp("^[" + s + "]*"), '');
}

String.prototype.mayBeFillHost = function(host) {
    var url = this.trim();
    if (!url) return '';
    if (url.startsWith(host)) return url;
    if (url.startsWith('//')) return host.split('//')[0] + url;

    return host.rtrim('/') + '/' + url.ltrim('/');
}

// --------------------------------------------------

var TypeChecker = {
    isString: function(o) {
        return typeof o == "string" || (typeof o == "object" && o.constructor === String);
    }, // https://stackoverflow.com/a/9729103
    isNumber: function(o) {
        return typeof o == "number" || (typeof o == "object" && o.constructor === Number);
    }, // https://stackoverflow.com/a/9729103
    isArray: function(o) {
        return o instanceof Array;
    },
    isFunction: function(o) {
        return o && {}.toString.call(o) === '[object Function]';
    }, // https://stackoverflow.com/a/7356528
    isObject: function(o) {
        return typeof o === 'object' && o !== null;
    }, // https://stackoverflow.com/a/8511332
};

// --------------------------------------------------

function log(o, msg) {
    Console.log('___' + (msg || '') + '___');
    if (TypeChecker.isArray(o)) {
        Console.log(JSON.stringify(o, null, 2));
    }
    else {
        Console.log(o);
    }
}

function cleanHtml(html) {
    html = html.replace(/\n/g, '<br>');
    // remove duplicate br tags
    html = html.replace(/(<br>\s*){2,}/gm, '<br>');
    // strip html comments
    html = html.replace(/<!--[^>]*-->/gm, '');
    // html decode
    html = html.replace(/&nbsp;/g, '');
    // trim br tags
    html = html.replace(/(^(\s*<br>\s*)+|(<br>\s*)+$)/gm, '');

    return html.trim();
}


// --------------------------------------------------

var $ = {
    Q: function(e, q, i) {
        var _empty = Html.parse('').select('body');

        var els = e.select(q);
        if (els == '' || els.size() == 0) return _empty;
        if (i == undefined) return els.first();

        if (typeof(i) == 'number') {
            if (i == -1) return els.last();
            if (i >= els.size()) return _empty;

            return els.get(i);
        } else {
            if (i.remove) {
                els.select(i.remove).remove();
            }
            return els;
        }
    },
    QA: function(e, q, o) {
        var arr = [];
        var els = e.select(q);
        o = o || {};

        if (els == '' || els.size() == 0) return o.j ? '' : arr;

        var processItem = function(item) {
            if (o.f) {
                if (o.f(item)) arr.push(o.m ? o.m(item) : item);
            } else {
                arr.push(o.m ? o.m(item) : item);
            }
        }

        var count = els.size();
        
        if (o.reverse) {
            for (var i = count - 1; i >= 0; i--) {
                var item = els.get(i);
                processItem(item);
            }
        } else {
            for (var i = 0; i < count; i++) {
                var item = els.get(i);
                processItem(item);
            }
        }

        if (o.j && typeof(o.j) == 'string') return arr.join(o.j);

        return arr;
    }

}

function execute(url) {
    url = url.replace(/^(?:https?:\/\/)?(?:[^@\n]+@)?(?:www\.)?([^:\/\n?]+)/img, BASE_URL);
    var browser = Engine.newBrowser() // Khá»Ÿi táº¡o browser
    doc = browser.launch(url, 4000)
    browser.close()
    var htm = doc.select(".txtnav")
    htm.select(".contentadv").remove()
    htm.select(".bottom-ad").remove()
    htm.select(".txtinfo").remove()
    htm.select("#txtright").remove()
    htm.select("h1").remove()
    htm = htm.html()
    htm = cleanHtml(htm)
        .replace(/^ç¬¬\d+ç« .*?<br>/, '') // Ex: 'â€ƒâ€ƒç¬¬11745ç«  å¤§ç»“å±€ï¼Œç»ˆ<br>'
        .replace('(æœ¬ç« å®Œ)', '')
        ;
    return Response.success(htm);
}
if (typeof execute !== 'function') throw new Error('Missing function: execute');
execute.apply(null, Array.prototype.map.call(__args, function(value) { return '' + value; }));

