/*
 * Copyright (C) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/// <reference path="model.ts" />
/// <reference path="constants.ts" />

declare const ReactDOMServer: any;
declare const ReactRouter: any;
declare const createReactClass: any;

// node_modules/@types/react-addons-css-transition-group doesn't work, so use ':any' instead.
var ReactCSSTransitionGroup: any = isServerSide() ? null :
  reactCreateFactory(window['ReactTransitionGroup'].CSSTransitionGroup);


// backw compat, later, do once per file instead (don't want a global 'r').
var r = React.DOM;

// Let this be a function, not a variable, so it can be used directly.
// (Otherwise there's a server side reactCreateFactory-not-yet-inited error)
function reactCreateFactory(x) {
  return React['createFactory'](x);
}


function isServerSide(): boolean {
  return !!window['ReactDOMServer'];
}


// Use this function to call getBoundingClientRect() and other stuff just before the next repaint,
// to avoid forced refresh of the layout.
function doNextFrameOrNow(something: () => void) {
  if (window.requestAnimationFrame) {
    window.requestAnimationFrame(something)
  }
  else {
    something();
  }
}


/**
 * Basic stuff needed by essentially all modules / files.
 */
//------------------------------------------------------------------------------
   module debiki2 {
//------------------------------------------------------------------------------

// E2e tests won't compile without this. Why not, React.js already included above? Oh well.
//declare var React;
// declare var ReactRouter;

export var Link: any = r.p({}, "LINK UNIMPL"); // reactCreateFactory(ReactRouter.Link);


export function die(errorMessage: string) {
  var dialogs: any = debiki2['pagedialogs'];
  // I don't remember why I added setTimeout() but there was a good reason.
  setTimeout(() => {
    debiki2['Server'].logError(errorMessage);
  });
  if (dialogs && dialogs.showAndThrowClientSideError) {
    dialogs.showAndThrowClientSideError(errorMessage);
  }
  else {
    // Server side.
    throw new Error(errorMessage);
  }
}

export function dieIf(condition, errorMessage: string) {
  if (condition) {
    die(errorMessage);
  }
}


export function logError(errorMessage: string) {
  // Why setTimeout()? I don't remember, see above in die(errorMessage).
  setTimeout(() => {
    debiki2['Server'].logError(errorMessage);
  });
}


export function logErrorIf(condition, errorMessage: string) {
  if (condition) {
    logError(errorMessage);
  }
}


export function scrollToBottom(node) {
  dieIf(!node, 'DwE9FMW2');
  node.scrollTop = node.scrollHeight;
}


export function anyE2eTestPassword() {
  return (window.location.search.match(/e2eTestPassword=([^&#]+)/) || [])[1];
}

export function anyForbiddenPassword() {
  return (window.location.search.match(/forbiddenPassword=([^&#]+)/) || [])[1];
}


export var findDOMNode = isServerSide() ? null : window['ReactDOM'].findDOMNode;
dieIf(!isServerSide() && !findDOMNode, 'EsE6UMGY2');


export function hasErrorCode(request: HttpRequest, statusCode: string) {
  return request.responseText && request.responseText.indexOf(statusCode) !== -1;
}


export function toId(x: number | { id: number } | { uniqueId: number }): number {
  if (_.isNumber(x)) return <number> x;
  var nr = x['id'];
  if (_.isNumber(nr)) return <number> nr;
  return x['uniqueId'];
}


export function isBlank(x): boolean {
  return _.isEmpty(x) || !x.trim();
}


export function nonEmpty(x): boolean {
  return !_.isEmpty(x);
}


export function isDefined2(x): boolean {
  return !_.isUndefined(x);
}


// Ooops bad name, shouldn't include null  CLEAN_UP rename to isPresent/isSomething/isSth/hasValue?
export function isDefined(x): boolean {  // rename to isNotNullOrUndefined(x)
  return !isNullOrUndefined(x);
}


export function isNullOrUndefined(x): boolean {   // RENAME to isNullOrUndef/isAbsent/lacksValue?
  return _.isNull(x) || _.isUndefined(x);
}


export function firstDefinedOf(x, y, z?) {
  return !_.isUndefined(x) ? x : (!_.isUndefined(y) ? y : z);
}


// Finds and replaces (in-place) the first item with item.id = replacement.id.
// Dies, if there's not matching item.
export function replaceById(itemsWithId: any[], replacement) {
  // @ifdef DEBUG
  dieIf(isNullOrUndefined(replacement.id), 'EdE4GJTH02');
  // @endif

  for (let i = 0; i < itemsWithId.length; ++i) {
    const item = itemsWithId[i];
    // @ifdef DEBUG
    dieIf(isNullOrUndefined(item.id), 'EdE2FJ0U7');
    // @endif

    if (item.id === replacement.id) {
      itemsWithId[i] = replacement;
      break;
    }
    dieIf(i === itemsWithId.length - 1, 'EdE8KA0N2');
  }
}


export function deleteById(itemsWithId: any[], idToDelete) {
  for (let i = 0; i < itemsWithId.length; ++i) {
    const item = itemsWithId[i];
    // @ifdef DEBUG
    dieIf(isNullOrUndefined(item.id), 'EdE6JHW0U2');
    // @endif
    if (item.id === idToDelete) {
      itemsWithId.splice(i, 1);
      break;
    }
  }
}


//------------------------------------------------------------------------------
}
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
