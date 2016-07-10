// Generated by CoffeeScript 1.10.0
(function() {
  var bind = function(fn, me){ return function(){ return fn.apply(me, arguments); }; };

  window.Incsearch = (function() {
    Incsearch.CHUNK_SIZE = 1000;

    Incsearch.MAX_RESULT = 1000;

    Incsearch.search_id = 0;

    function Incsearch(dom) {
      this.dom = dom;
      this.highlight_query = bind(this.highlight_query, this);
      this.highlight_as_is = bind(this.highlight_as_is, this);
      this.highlight_substring = bind(this.highlight_substring, this);
      this.build_highlighters = bind(this.build_highlighters, this);
      this.match_containing_substrings = bind(this.match_containing_substrings, this);
      this.match_containing = bind(this.match_containing, this);
      this.match_containing_as_is = bind(this.match_containing_as_is, this);
      this.match_beginning_substrings = bind(this.match_beginning_substrings, this);
      this.match_beginning = bind(this.match_beginning, this);
      this.match_beginning_as_is = bind(this.match_beginning_as_is, this);
      this.build_regexps = bind(this.build_regexps, this);
      this.update_search_results = bind(this.update_search_results, this);
      this.search_in_chunk = bind(this.search_in_chunk, this);
      this.run = bind(this.run, this);
    }

    Incsearch.prototype.create = function(search_list, fn) {
      var i, l, len, lists, ref, search_cb, symbol, that, type;
      this.search_list = search_list;
      this.dom.empty();
      this.dom.append('<div id=\'incsearch-header-area\'>\n  <input id=\'incsearch-box\' type=\'search\' results=5 autosave=\'index\' placeholder=\'Search\'>\n</div>\n<div id=\'incsearch-content-area\'>\n  <div id=\'incsearch-list\' class=\'incsearch-select-pane\'>\n    <ul>\n    </ul>\n  </div>\n  <div id=\'incsearch-result\' class=\'incsearch-select-pane\'>\n    <ul>\n    </ul>\n  </div>\n</div>\n<div id=\'incsearch-footer-area\'>\n  <input id=\'incsearch-close\' type=\'button\' value=\'Close\'>\n</div>');
      this.dom.css('display', 'block');
      that = this;
      $('.incsearch-select-pane ul').on('click', 'li', function() {
        var i;
        i = $(this).attr('data-order');
        return fn(that.search_list[i].symbol);
      });
      $('#incsearch-list ul').empty();
      len = this.search_list.length;
      lists = [];
      for (i = l = 0, ref = len; 0 <= ref ? l < ref : l > ref; i = 0 <= ref ? ++l : --l) {
        type = this.search_list[i].type;
        symbol = this.escape_txt(this.search_list[i].symbol);
        lists.push("<li data-order='" + i + "' class='" + type + "'>" + symbol + "</a></li>");
      }
      $('#incsearch-list ul').append(lists.join(''));
      search_cb = (function(_this) {
        return function() {
          var query;
          $('#incsearch-result ul').empty();
          query = $('#incsearch-box').val();
          if ((query != null) && query.length > 0) {
            $('#incsearch-list').css('display', 'none');
            $('#incsearch-result').css('display', 'block');
            return _this.run(query);
          } else {
            $('#incsearch-list').css('display', 'block');
            return $('#incsearch-result').css('display', 'none');
          }
        };
      })(this);
      $('#incsearch-box').on('keyup', search_cb);
      $('#incsearch-box').on('search', search_cb);
      return $('#incsearch-close').on('click', (function(_this) {
        return function() {
          return _this["delete"]();
        };
      })(this));
    };

    Incsearch.prototype["delete"] = function() {
      this.dom.empty();
      this.dom.css('display', 'none');
      return this.search_list = null;
    };

    Incsearch.prototype.run = function(query) {
      var highlighters, regexps, runner, state;
      if (query == null) {
        return;
      }
      query = query.toLowerCase();
      regexps = this.build_regexps(query);
      highlighters = this.build_highlighters(query);
      state = {
        'counter': 0,
        'matched': 0,
        'search_id': ++Incsearch.search_id
      };
      runner = (function(_this) {
        return function() {
          var results;
          if (state.search_id !== Incsearch.search_id) {
            return;
          }
          results = _this.search_in_chunk(query, regexps, highlighters, state);
          _this.update_search_results(results);
          if (state.counter < 5 * _this.search_list.length && state.matched < Incsearch.MAX_RESULT) {
            return setTimeout(runner, 1);
          }
        };
      })(this);
      return runner();
    };

    Incsearch.prototype.search_in_chunk = function(query, regexps, highlighters, state) {
      var highlighted, hlt_fn, i, j, k, l, len, match_fn, ref, ref1, results, symbol;
      results = [];
      len = this.search_list.length;
      for (k = l = 0, ref = Incsearch.CHUNK_SIZE; 0 <= ref ? l < ref : l > ref; k = 0 <= ref ? ++l : --l) {
        i = state.counter % len;
        j = Math.floor(state.counter / len);
        ++state.counter;
        if (j > 4) {
          break;
        }
        if (state[String(i)]) {
          continue;
        }
        ref1 = (function() {
          switch (j) {
            case 0:
              return [this.match_beginning_as_is, this.highlight_as_is];
            case 1:
              return [this.match_beginning_substrings, this.highlight_query];
            case 2:
              return [this.match_containing_substrings, this.highlight_query];
            case 3:
              return [this.match_beginning, this.highlight_query];
            case 4:
              return [this.match_containing, this.highlight_query];
            default:
              return [null, null];
          }
        }).call(this), match_fn = ref1[0], hlt_fn = ref1[1];
        symbol = this.search_list[i].symbol;
        if (match_fn(symbol, query, regexps, highlighters)) {
          state[String(i)] = true;
          highlighted = hlt_fn(symbol, query, regexps, highlighters);
          results.push({
            'index': i,
            'highlighted': highlighted
          });
          if (++state.matched > Incsearch.MAX_RESULT) {
            break;
          }
        }
      }
      return results;
    };

    Incsearch.prototype.update_search_results = function(results) {
      var i, l, len1, li, result, s, t;
      li = '';
      for (l = 0, len1 = results.length; l < len1; l++) {
        result = results[l];
        i = result.index;
        t = this.search_list[i].type;
        s = this.escape_txt(result.highlighted).split('\u0001').join('<b>').split('\u0002').join('</b>');
        li += "<li data-order='" + i + "' class='" + t + "'>" + s + "</li>";
      }
      return $('#incsearch-result ul').append(li);
    };

    Incsearch.prototype.build_regexps = function(query) {
      var converter, l, len1, q, queries, results1;
      queries = $.grep(query.split(/\s+/), function(s) {
        return s.match(/\S/);
      });
      converter = function(s) {
        if ('\\*+.?{}()[]^$-|'.indexOf(s) !== -1) {
          s = '\\' + s;
        }
        return "([" + s + "])([^" + s + "]*?)";
      };
      results1 = [];
      for (l = 0, len1 = queries.length; l < len1; l++) {
        q = queries[l];
        results1.push(new RegExp(q.replace(/(.)/g, converter), 'i'));
      }
      return results1;
    };

    Incsearch.prototype.match_beginning_as_is = function(symbol, query, regexps) {
      return symbol.toLowerCase().indexOf(query) === 0;
    };

    Incsearch.prototype.match_beginning = function(symbol, query, regexps) {
      var l, len1, ls, q, r, ref;
      q = query.split(/\s+/)[0];
      ls = symbol.toLowerCase();
      if (ls.indexOf(q) !== 0) {
        return false;
      }
      ref = regexps.slice(1);
      for (l = 0, len1 = ref.length; l < len1; l++) {
        r = ref[l];
        if (!ls.match(r)) {
          return false;
        }
      }
      return true;
    };

    Incsearch.prototype.match_beginning_substrings = function(symbol, query, regexps) {
      var i, l, pos, queries, ref;
      queries = query.split(/\s+/);
      for (i = l = 0, ref = queries.length; 0 <= ref ? l < ref : l > ref; i = 0 <= ref ? ++l : --l) {
        pos = symbol.toLowerCase().indexOf(queries[i]);
        if (i === 0 && pos !== 0) {
          return false;
        } else if (pos < 0) {
          return false;
        }
      }
      return true;
    };

    Incsearch.prototype.match_containing_as_is = function(symbol, query, regexps) {
      return symbol.toLowerCase().indexOf(query) >= 0;
    };

    Incsearch.prototype.match_containing = function(symbol, query, regexps) {
      var l, len1, ls, q, r, ref;
      q = query.split(/\s+/)[0];
      ls = symbol.toLowerCase();
      if (!(ls.indexOf(q) > 0)) {
        return false;
      }
      ref = regexps.slice(1);
      for (l = 0, len1 = ref.length; l < len1; l++) {
        r = ref[l];
        if (!ls.match(r)) {
          return false;
        }
      }
      return true;
    };

    Incsearch.prototype.match_containing_substrings = function(symbol, query, regexps) {
      var l, len1, pos, q, queries;
      queries = query.split(/\s+/);
      for (l = 0, len1 = queries.length; l < len1; l++) {
        q = queries[l];
        pos = symbol.toLowerCase().indexOf(q);
        if (pos < 0) {
          return false;
        }
      }
      return true;
    };

    Incsearch.prototype.build_highlighters = function(query) {
      var i, l, len1, q, queries, results1;
      queries = $.grep(query.split(/\s+/), function(s) {
        return s.match(/\S/);
      });
      results1 = [];
      for (l = 0, len1 = queries.length; l < len1; l++) {
        q = queries[l];
        results1.push(((function() {
          var m, ref, results2;
          results2 = [];
          for (i = m = 0, ref = q.length; 0 <= ref ? m < ref : m > ref; i = 0 <= ref ? ++m : --m) {
            results2.push('\u0001$' + (i * 2 + 1) + '\u0002$' + (i * 2 + 2));
          }
          return results2;
        })()).join(''));
      }
      return results1;
    };

    Incsearch.prototype.highlight_substring = function(s, pos, len) {
      return s.slice(0, pos) + '\u0001' + s.slice(pos, pos + len) + '\u0002' + s.slice(pos + len);
    };

    Incsearch.prototype.highlight_as_is = function(symbol, query, regexps, highlighters) {
      var pos;
      pos = symbol.toLowerCase().indexOf(query);
      return this.highlight_substring(symbol, pos, query.length);
    };

    Incsearch.prototype.highlight_query = function(symbol, query, regexps, highlighters) {
      var i, l, len, pos, q, ref;
      q = query.split(/\s+/)[0];
      len = q.length;
      pos = symbol.toLowerCase().indexOf(q);
      symbol = this.highlight_substring(symbol, pos, len);
      for (i = l = 1, ref = regexps.length; 1 <= ref ? l < ref : l > ref; i = 1 <= ref ? ++l : --l) {
        symbol = symbol.replace(regexps[i], highlighters[i]);
      }
      return symbol;
    };

    Incsearch.prototype.escape_txt = function(s) {
      return s.split('&').join('&amp;').split('<').join('&lt;').split('>').join('&gt;');
    };

    return Incsearch;

  })();

}).call(this);

//# sourceMappingURL=incsearch.js.map