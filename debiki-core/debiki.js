// vim: fdm=marker et ts=2 sw=2

threadHovered = null;

jQuery.noConflict()(function($){

$(".post, .thread-summary").hover(
  function(event){
    var nextThread = $(this).closest('.thread');

    if ($(this).hasClass('post')) {
      // Show the #action-menu, unless the thread is closing,
      // and unless the thread itself is already a .reply thread.
      if (!nextThread.hasClass('collapsed') && !nextThread.hasClass('reply')) {
        $(this).after($('#action-menu'))
      }
    }
    else if (nextThread.find('#action-menu').length) {
      // This is a .thread-summary and it has the #action-menu inside.
      // This is not a safe place for the menu! If this
      // .thread-summary is clicked, the thread will
      // collapsed itself, and the #action-menu will be hidden inside
      // the collapsed thread -- the menu becomes buggy gone -- unless
      // moved to somewhere safe.
      $('#hidden-menus').append($('#action-menu'));
    }

    if (threadHovered && threadHovered[0] == nextThread[0])
      return;

    if ($(this).hasClass('thread-summary') &&
        !threadHovered.hasClass('collapsed')) {
      // This .thread-summary is not visible (it's only visible by default
      // if the thread is collapsed). Don't fade it in, because the user
      // might be navigating the #action-menu, and then we don't want
      // this .thread-summary to appear below that menu. Instead,
      // only open the .thread-summary if the threaad's *post* is hovered.
      return;
    }

    // Fade last summary, unless thread collapsed.
    if (threadHovered && !threadHovered.hasClass('collapsed')) {
      threadHovered.children('.thread-summary')
                      .stop(true, true).fadeTo(1000, 0);
      threadHovered.stop(true, true)
          .removeClass('demarcated')
          .removeClass('demarcated-fx', 1000);
    }
    // Show summary for current thread.
    nextThread.children('.thread-summary').stop(true, true).fadeTo(600, 1);
    nextThread.stop(true, true)
        .addClass('demarcated') // gives functionality instantly
        .addClass('demarcated-fx', 600); // just for class animation effects
    threadHovered = nextThread;
  },
  function(event){
  });

$(".thread-summary").click(function() {
  var thread = $(this).closest(".thread");
  if (! thread.hasClass('demarcated')) return;
  thread
    .children(":not(.thread-summary)").stop(true,true).slideToggle(800)
    .end()
    .stop(true, true)
    .toggleClass('collapsed')
    .toggleClass('collapsed-fx', 600);
});

/*
$(".vote-summary").click(function() {
  $(this).closest(".thread").children(".post")
      .children(":not(.vote-summary)").slideToggle(200);
}); */

$(".post").resizable({ autoHide: true })
  // Remove the small grip, replace with the normal one:
  .find('.ui-resizable-se')
  .removeClass('.ui-icon-gripsmall-diagonal-se')
  .addClass('ui-icon-grip-diagonal-se');

$(".edit").hover(
  function(event){ $(this).append($("#edit-menu")); },
  function(event){ $("#hidden-menus").append($("#edit-menu")); }
  );

$(".vote").hover(
  function(event){ $(this).append($("#vote-menu")); },
  function(event){ $("#hidden-menus").append($("#vote-menu")); }
  );

/*
$(".reply").hover(
  function(event){ $(this).append($("#reply-menu")); },
  function(event){ $("#hidden-menus").append($("#reply-menu")); }
  );
*/

$("#action-menu .reply").click(function() {
  var post = $(this).closest(".thread").children(".post");
  var reply = $("#hidden-menus .reply-template").clone(true);
  var postId = post.attr('id').substr(5, 999); // drop initial "post-"
  reply.find("input[name='parent']").attr('value', postId);
  reply.find("input[name='author']").attr('value', 'Author unknown');
  post.after(reply);
  // Dismiss action menu
  $('#action-menu').appendTo($('#hidden-menus'));
});

$("#hidden-menus button.cancel").click(function() {
  $(this).closest('.thread.reply.preview').remove();
});

  //function(event){
  //  $(this).append($(
  //      "<ul class='menu'>"+
  //        "<li>Edit</li>"+
  //        "<li>Copy&#160;&amp;&#160;edit</li>"+
  //        "<li>Delete</li>"+
  //        "<li>Move</li>"+
  //      "</ul>"));
  //},
  //function(event){
  //  $(this).remove(".menu");
  //});

// Don't show the crosshair cursor for menu items that trigger no action.
$(".menu li:has(.sub.menu)").css("cursor", "default");

// Highlight the parent post when hovering over a reference.
$(".parent-ref").hover(
  function(event){
    $(this).closest(".thread").parent().closest(".thread").
            children(".post").addClass("highlight");
  },
  function(event){
    $(this).closest(".thread").parent().closest(".thread").
            children(".post").removeClass("highlight");
  });

// When the .reply.preview is not inside the #hidden-menu,
// but under a real .thread, leave it visible after hovering.
$(".thread. .reply.preview").hover(
  function(event){
    $(this).parents().css("overflow", "visible");
  },
  function(event){
    // leave overflow visible for now.
    // (It's (perhaps) annoying if the reply-textarea
    // is overflow-hidden, since the outline around it
    // will be cropped in a usually ugly manner.)
  });

});
