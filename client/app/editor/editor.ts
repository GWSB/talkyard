/*
 * Copyright (C) 2015 Kaj Magnus Lindberg (born 1979)
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

/// <reference path="../../shared/plain-old-javascript.d.ts" />
/// <reference path="../../typedefs/react/react.d.ts" />
/// <reference path="../../typedefs/modernizr/modernizr.d.ts" />
/// <reference path="../renderer/model.ts" />
/// <reference path="../Server.ts" />

//------------------------------------------------------------------------------
   module debiki2.editor {
//------------------------------------------------------------------------------

var d = { i: debiki.internal, u: debiki.v0.util };
var r = React.DOM;
var reactCreateFactory = React['createFactory'];
var ReactBootstrap: any = window['ReactBootstrap'];
var Button = reactCreateFactory(ReactBootstrap.Button);
var theEditor: any;
var $: any = window['jQuery'];


export function createEditor() {
  var editorElem = document.getElementById('dw-editor');
  if (editorElem) {
    theEditor = React.render(Editor({}), editorElem);
  }
}


export function toggleWriteReplyToPost(postId: number) {
  theEditor.toggleWriteReplyToPost(postId);
}


export function openEditorToEditPost(postId: number) {
  theEditor.editPost(postId);
}


export function openEditorToAddSummary(postId: number) {
  theEditor.addSummary(postId);
}


export function editNewForumPage(parentPageId: string, role: string) {
  theEditor.editNewForumPage(parentPageId, role);
}


export var Editor = createComponent({
  getInitialState: function() {
    return {
      visible: false,
      text: '',
      draft: '',
      safePreviewHtml: '',
      replyToPostIds: [],
      newForumPageParentId: null,
      newForumPageRole: null,
    };
  },

  componentWillMount: function() {
     this.updatePreview = _.debounce(this.updatePreview, 333);
  },

  componentDidMount: function() {
    this.startMentionsParser();
    this.makeEditorResizable();
  },

  startMentionsParser: function() {
    $(this.refs.textarea.getDOMNode()).atwho({
      at: "@",
      search_key: 'username',
      tpl: "<li data-value='${atwho-at}${username}'>${username} (${fullName})</li>",
      callbacks: {
        remote_filter: (prefix, callback) => {
          Server.listUsernames(prefix, callback);
        }
      }
    });
  },

  makeEditorResizable: function() {
    if (d.i.isInEmbeddedEditor) {
      // The iframe is resizable instead.
      return;
    }
    var placeholder = $(this.refs.placeholder.getDOMNode());
    var editor = $(this.refs.editor.getDOMNode());
    editor.css('border-top', '8px solid #888');
    editor.resizable({
      handles: 'n',
      resize: function() {
        placeholder.height(editor.height());
      }
    });
  },

  toggleWriteReplyToPost: function(postId: number) {
    if (this.alertBadState('WriteReply'))
      return;
    var postIds = this.state.replyToPostIds;
    var index = postIds.indexOf(postId);
    if (index === -1) {
      postIds.push(postId);
      this.showEditor();
    }
    else {
      postIds.splice(index, 1);
    }
    this.setState({
      replyToPostIds: postIds,
      text: this.state.text ? this.state.text : this.state.draft
    });
    if (!postIds.length) {
      this.closeEditor();
    }
  },

  addSummary: function(postId: number) {
    if (this.alertBadState('AddSummary'))
      return;
    if (!postId) {
      // The page title has id 0 but cannot be summarized.
      alert('Cannot summarize: ' + postId);
      return;
    }
    this.showEditor();
    this.setState({
      addingSummaryToPostId: postId,
      text: "### Title of thread. Delete this line if you don't want a title.\n\n" +
          "Type a summary here. Delete this line if you don't want a summary."
    });
    this.updatePreview();
  },

  editPost: function(postId: number) {
    if (this.alertBadState())
      return;
    Server.loadCurrentPostText(postId, (text: string) => {
      this.showEditor();
      this.setState({
        editingPostId: postId,
        text: text
      });
      this.updatePreview();
    });
  },

  editNewForumPage: function(parentPageId: string, role: string) {
    if (this.alertBadState())
      return;
    this.showEditor();
    this.setState({
      newForumPageParentId: parentPageId,
      newForumPageRole: role,
      text: this.state.text ? this.state.text : this.state.draft
    });
  },

  alertBadState: function(wantsToDoWhat = null) {
    if (wantsToDoWhat !== 'WriteReply' && this.state.replyToPostIds.length > 0) {
      alert('Please first finish writing your post');
      return true;
    }
    if (_.isNumber(this.state.editingPostId)) {
      alert('Please first save your current edits');
      // If this is an embedded editor, for an embedded comments page, that page
      // will now have highlighted some reply button to indicate a reply is
      // being written. But that's wrong, clear those marks.
      if (d.i.isInEmbeddedEditor) {
        window.parent.postMessage(
          JSON.stringify(['clearIsReplyingMarks', {}]), '*');
      }
      else {
        d.i.clearIsReplyingMarks();
      }
      return true;
    }
    if (this.state.newForumPageRole) {
      var what = this.state.newForumPageRole === 'ForumTopic' ? 'topic' : 'category';
      alert('Please first either save or cancel your new forum ' + what);
      d.i.clearIsReplyingMarks();
      return true;
    }
    if (this.state.addingSummaryToPostId) {
      alert("Please first finish the title or summary you have started writing");
      return true;
    }
    return false;
  },

  onTextEdited: function(event) {
    this.setState({
      text: event.target.value
    });
    this.updatePreview();
  },

  updatePreview: function() {
    d.i.loadEditorDependencies().done(() => {
      if (!this.isMounted())
        return;
      // (COULD verify is mounted and still edits same post/thing, or not needed?)
      var isEditingBody = this.state.editingPostId === d.i.BodyId;
      var sanitizerOpts = {
        allowClassAndIdAttr: isEditingBody,
        allowDataAttr: isEditingBody
      };
      var htmlText = d.i.markdownToSafeHtml(this.state.text, window.location.host, sanitizerOpts);
      this.setState({
        safePreviewHtml: htmlText
      });
    });
  },

  onCancelClick: function() {
    this.closeEditor();
  },

  onSaveClick: function() {
    if (this.state.newForumPageRole) {
      this.saveNewForumPage();
    }
    else if (_.isNumber(this.state.editingPostId)) {
      this.saveEdits();
    }
    else if (this.state.addingSummaryToPostId) {
      this.saveSummary();
    }
    else {
      this.saveNewPost();
    }
  },

  saveEdits: function() {
    Server.saveEdits(this.state.editingPostId, this.state.text, () => {
      this.closeEditor();
    });
  },

  saveSummary: function() {
    ReactActions.saveSummary(this.state.addingSummaryToPostId,
        this.state.text, this.closeEditor);
  },

  saveNewPost: function() {
    Server.saveReply(this.state.replyToPostIds, this.state.text, () => {
      this.closeEditor();
    });
  },

  saveNewForumPage: function() {
    var title = $(this.refs.titleInput.getDOMNode()).val();
    var data = {
      parentPageId: this.state.newForumPageParentId,
      pageRole: this.state.newForumPageRole,
      pageStatus: 'Published',
      pageTitle: title,
      pageBody: this.state.text
    };
    Server.createPage(data, (newPageId: string) => {
      window.location.assign('/-' + newPageId);
    });
  },

  showEditor: function() {
    this.setState({ visible: true });
    if (d.i.isInEmbeddedEditor) {
      window.parent.postMessage(JSON.stringify(['showEditor', {}]), '*');
    }
  },

  closeEditor: function() {
    this.setState({
      visible: false,
      replyToPostIds: [],
      editingPostId: null,
      addingSummaryToPostId: null,
      newForumPageParentId: null,
      newForumPageRole: null,
      text: '',
      draft: _.isNumber(this.state.editingPostId) ? '' : this.state.text,
      safePreviewHtml: '',
    });
    // Remove any is-replying highlights.
    if (d.i.isInEmbeddedEditor) {
      window.parent.postMessage(JSON.stringify(['hideEditor', {}]), '*');
    }
    else {
      // (Old jQuery code.)
      $('.dw-replying').removeClass('dw-replying');
    }
  },

  render: function() {
    var titleInput;
    if (this.state.newForumPageRole) {
      var defaultTitle = this.state.newForumPageRole === 'ForumTopic' ?
          'Topic title' : 'Category title';
      titleInput =
          r.input({ className: 'title-input', type: 'text', ref: 'titleInput',
              key: this.state.newForumPageRole, defaultValue: defaultTitle });
    }

    var doingWhatInfo;
    var editingPostId = this.state.editingPostId;
    var addingSummaryToPostId = this.state.addingSummaryToPostId;
    if (_.isNumber(editingPostId)) {
      doingWhatInfo =
        r.div({},
          'Editing ', r.a({ href: '#post-' + editingPostId }, 'post ' + editingPostId + ':'));
    }
    else if (addingSummaryToPostId) {
      doingWhatInfo =
        r.div({},
          'Adding title or summary for ', r.a({ href: '#post-' + addingSummaryToPostId },
            'post ' + addingSummaryToPostId + ':'));
    }
    else if (this.state.newForumPageRole === 'ForumTopic') {
      doingWhatInfo = r.div({}, 'New topic title and text:');
    }
    else if (this.state.newForumPageRole === 'ForumCategory') {
      doingWhatInfo = r.div({}, 'New category title and text:');
    }
    else if (this.state.replyToPostIds.length === 0) {
      doingWhatInfo =
        r.div({}, 'Please select one or more posts to reply to.');
    }
    else if (this.state.replyToPostIds.length > 0) {
      doingWhatInfo =
        r.div({},
          'Replying to ',
          this.state.replyToPostIds.map((postId, index) => {
            var anyAnd = index > 0 ? ' and ' : '';
            return (
              r.span({},
                anyAnd,
                r.a({ href: '#post-' + postId }, 'post ' + postId)));
          }),
          ':');
    }

    var saveButtonTitle = 'Save';
    if (_.isNumber(this.state.editingPostId)) {
      saveButtonTitle = 'Save edits';
    }
    if (this.state.addingSummaryToPostId) {
      saveButtonTitle = 'Add';
    }
    else if (this.state.replyToPostIds.length) {
      saveButtonTitle = 'Post reply';
    }
    else if (this.state.newForumPageRole === 'ForumTopic') {
      saveButtonTitle = 'Create new topic';
    }
    else if (this.state.newForumPageRole === 'ForumCategory') {
      saveButtonTitle = 'Create new category';
    }

    // If not visible, don't remove the editor, just hide it, so we won't have
    // to unrigister the mentions parser (that would be boring).
    var styles = {
      display: this.state.visible ? 'block' : 'none'
    };

    // Make space for the soft keyboard on touch devices.
    var maxHeightCss = !Modernizr.touch || debiki2.utils.isMouseDetected ? undefined : {
      maxHeight: screen.height / 2.5
    };

    return (
      r.div({ style: styles },
        r.div({ id: 'debiki-editor-placeholder', ref: 'placeholder' }),
        r.div({ id: 'debiki-editor-controller', ref: 'editor', style: maxHeightCss },
          r.div({ id: 'editor-after-borders' },
            r.div({ className: 'editor-area' },
              r.div({ className: 'editor-area-after-borders' },
                doingWhatInfo,
                titleInput,
                r.div({ className: 'editor-wrapper' },
                  r.textarea({ className: 'editor', ref: 'textarea', value: this.state.text,
                      onChange: this.onTextEdited })))),
            r.div({ className: 'preview-area' },
              r.div({}, titleInput ? 'Preview: (title excluded)' : 'Preview:'),
              r.div({ className: 'preview',
                  dangerouslySetInnerHTML: { __html: this.state.safePreviewHtml }})),
            r.div({ className: 'submit-cancel-btns' },
              Button({ onClick: this.onSaveClick }, saveButtonTitle),
              Button({ onClick: this.onCancelClick }, 'Cancel'))))));
  }
});


//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: fdm=marker et ts=2 sw=2 tw=0 fo=r list
