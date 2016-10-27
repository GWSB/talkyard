EffectiveDiscussions
=============================

EffectiveDiscussions (ED) is a combined forum, chat and question-answers platform,
inspired by Discourse, Slack and StackOverflow.

Screenshots below.<br>
See it live: https://www.effectivediscussions.org/forum/latest<br>
Read about it: https://www.effectivediscussions.org/

Organizations often create a forum (e.g. Discourse) and a chat (Slack/Gitter/Discord),
and use StackOverflow — then they split their community.
With EffectiveDiscussions, you can gather your community in one place.<br>
(You can use ED for your website too, if you know HTML & CSS well and have lots of patience.)

ED gives you both traditional "flat-chat" comments (in chat topics),
and Hacker News / Reddit style threaded comments, with some improvements.

Simple installation, if you know a bit about Docker. Automatic upgrades coming soon.
One installation can host many sites.
(There's a hosted solution too, if you don't want to maintain a server yourself. See the 'read about it' link above.)

### Screenshots

Forum index:

![ed-demo-forum-index](https://cloud.githubusercontent.com/assets/7477359/19650764/bb3a1450-9a0a-11e6-884d-d23c93476db3.jpg)

Chat:

![ed-e2e-chat-owen-maria](https://cloud.githubusercontent.com/assets/7477359/19674424/608c49aa-9a88-11e6-8ccd-c2e7ceebd0c2.jpg)

Hacker News / Reddit style discussion:

![ed-discussion-semantics-of-upvote-2013](https://cloud.githubusercontent.com/assets/7477359/19650769/bea906aa-9a0a-11e6-8ea2-9ad771981f46.jpg)

Good user experience (at least we're trying) — for example there's an admin-getting-started guide
for you, if you create a forum:

![ed-admin-intro-guide](https://cloud.githubusercontent.com/assets/7477359/19679591/99a12098-9aa2-11e6-8b65-705c2548cbea.jpg)

Users online:

![ed-online-users](https://cloud.githubusercontent.com/assets/7477359/19680424/f0353f86-9aa5-11e6-84d9-94d46f228b93.jpg)


### Not totally ready

Development stage: Between Alpha and Beta. So, don't use ED for anything that must be
up-and-running always, or anything top secret (there might be security bugs), right now.

%% Secure
%%
%% We use secure HTTP (HTTPS). We don't sell your data. However ED is under development,
%% and there might be security bugs. Right now, don't use ED for anything top secret.

Open source

Tech users: Install with Docker on your own server. Automatic upgrades (coming soon).
One installation can host many forums.


### How install?

_This_ repository is for writing ED source code.
To _install_ ED, instead go to: https://github.com/debiki/ed-prod-one
("ed-prod-one" means "production installation on one single server").


Getting Started
-----------------------------

#### Before you start

You'll need about 3GB free memory. And a somewhat fast internet connection — you'll be downloading perhaps 0.5 (?) GB Docker images.

Install Docker-Compose, version 1.7.0+: https://docs.docker.com/compose/install/

#### The instructions

1. Clone this repository, `cd` into it. Then update submodules:

        git clone ... ed
        cd ed
        git submodule update --init

1. Edit the system config so that ElasticSearch will work: (run this as one single command, not one line at a time)

        sudo tee -a /etc/sysctl.conf <<EOF

        ###################################################################
        # EffectiveDiscussions settings
        #
        # Up the max backlog queue size (num connections per port), default = 128
        net.core.somaxconn=8192
        # ElasticSearch requires (at least) this, default = 65530
        vm.max_map_count=262144
        EOF

    (`max_map_count` docs: https://www.kernel.org/doc/Documentation/sysctl/vm.txt)

    Reload the system config:

        sudo sysctl --system

1. Start everything: (this will take a while, the first time: some Docker images will be downloaded and built)

        docker-compose up  # use 'sudo' if needed

1. Point your browser to http://localhost/ and sign up as admin, with email `admin@example.com`.
   As username, you can type `admin`, and password e.g. `public1234`.

   You'll be asked to confirm your email address, by clicking a link in an email
   that was sent to you — but in fact the email couldn't be sent, because you haven't configured
   any email server, and `admin@example.com` isn't your address anyway.

   Instead look in the app server log file: `docker-compose logs app`. There you'll find
   the email — it's written to the log files, in development mode. Copy the
   confirmation link from the `<a href=...>` and paste it in the browser's address bar.


Tests
-----------------------------

#### End-to-end tests

See the [End-to-end tests Readme](./docs/e2e-tests-readme.md)
And, if you want to test in a browser other than Chrome, see [Making *.localhost addresses work](./docs/wildcard-dot-localhost.md).


#### Security tests

... Soon ...


#### Unit tests

Stop everything: `docker-compose down` and then: `scripts/cli.sh` then type `test` + hit Enter.


#### Performance tests

Append to `/etc/security/limits.conf` ... hmm but now with Docker-Compose, which container?

    your_login_name hard nofile 65535
    your_login_name soft nofile 65535


Technology
-----------------------------

- Client: React.js, TypeScript, Webdriver.io.
- Server: Scala and Play Framework. Nginx, Nchan, some Lua. React.js in Java's Nashorn Javascript engine.
- Databases: PostgreSQL, Redis, ElasticSearch.


Contributing
-----------------------------

If you'd like to contribute, read more
[at the end of this page](https://www.effectivediscussions.org/dev/-81n25/technical-information) about contributing.

In the future, I suppose there will be a Contributor License Agreement (CLA), similar to
[Google's CLA](https://developers.google.com/open-source/cla/individual) — you'd open
source your code and grant me a copyrigt license.


Directories
-----------------------------

This project looks like so:


    server/
     |
     +-docker-compose.yml   <-- tells Docker how to run EffectiveDiscussions
     |
     +-client/         <-- Javascript, CSS, React.js components
     | +-app/          <-- Client side code
     | +-server/       <-- React.js components rendered server side
     | +-test/         <-- End-to-end tests and Javascript unit tests
     |
     +-app/            <-- Scala code — a Play Framework 2 app
     |
     +-modules/
     | +-ed-dao-rdb/        <-- A database access object (DAO), for PostgreSQL
     | +-ed-core/           <-- Code shared by the DAO and by the ./app/ code
     | +-ed-prod-one-test/  <-- A production installation, for automatic tests
     | |
     | +-local/        <-- Ignored by .gitignore. Here you can override the
     | |                   default config values. If you want to, turn it into
     | |                   a Git repo.
     | |
     | ...Third party modules
     |
     +-public/         <-- Some images and libs, plus JS and CSS that Gulp
     |                     has bundled and minified from the client/ dir above.
     |
     +-docker/         <-- Dockerfiles for all docker-compose containers
     | +-web/          <-- Docker build stuff for the Nginx container
     | | +-modules/
     | |   +-nchan/    <-- WebSocket and PubSub for Nginx (a Git submodule)
     | |   +-luajit/   <-- Lua
     | |   ...
     | |
     | +-gulp/         <-- Container that runs Node.js and bundles JS and CSS
     | +-gulp-home/    <-- Mounted as Gulp container home-dir = disk cache
     | |
     | +-...           <-- More containers...
     | |
     | +-data/
     |   +-rdb         <-- Mounted as a volume in the Postgres container
     |   +-cache       <-- Mounted in the Redis container
     |   +-uploads     <-- Mounted read-write in the Play container, but
     |   |                 read-only in Nginx (to serve static files)
     |   ...
     |
     +-scripts/        <-- Utility scripts
     |
     +-conf/           <-- Default config files that assume everything
                           is installed on localohost, and dev mode

Naming style
-----------------------------

### CSS classes and ids

*Example*: `s_P_By_FN-Gst`. Here, `s_` is a prefix used for all classes, and
it means "some". For ids we use `t_` instead, means "the". `P` means Post. `By` means
who-was-it-written-By. `FN` means Full Name. `Gst` means Guest.

So, this is BEM (Block Element Modifier) with a few tweaks: Blocks/elements are separated with
only one underscore, and modifiers with only one dash. Blocks, elems and modifiers always
start with uppercase — because then it's easy to tell if we're dealing with an _abbreviation_
or not. For example, `FN` (full name) is an abbreviation. But `By` is not (since it continues with
lowercase letters).

For stuff with otherwise no class or id, and that should be clicked in end-to-end tests,
we use classes only, and the prefix `e_` (instead of `s_` or `t_`).


Custom third party builds
-----------------------------

We're building & using a smaller version of Lodash, like so:
(this makes slim-bundle.min.js.gz 8kb = 4% smaller, as of September 2016)

    node_modules/lodash-cli/bin/lodash  include=assign,assignIn,before,bind,chain,clone,compact,concat,create,debounce,defaults,defer,delay,each,escape,every,filter,find,findLast,flatten,flattenDeep,forEach,forOwn,has,head,includes,identity,indexOf,isArguments,isArray,isBoolean,isDate,isEmpty,isEqual,isFinite,isFunction,isNaN,isNull,isNumber,isObject,isRegExp,isString,isUndefined,iteratee,keys,last,map,matches,max,min,mixin,negate,noConflict,noop,once,pick,reduce,remove,result,size,slice,some,sortBy,sumBy,take,tap,throttle,thru,toArray,uniq,uniqBy,uniqueId,value,values \
      --output client/third-party/lodash-custom.js

- For security reasons, we checkin only the resulting `.js` file (but not the `.min.js`) file
into source control (so that you can read the source code and see what it does).
- There are some Gulp plugins that builds Lodash but one seems abandonend (gulp-lodash-builder)
and the other (gulp-lodash-custom) analyzes all .js files, I guess that'd slow down the build
rather much + won't immediately work with Typescript?


Old Code
-----------------------------

In January 2015 I squashed all old 4300+ commits into one single commit,
because in the past I did some mistakes, so it feels better to start over again
from commit number 1. The old commit history is available here:
https://github.com/debiki/debiki-server-old


Credits
-----------------------------

I sometimes copy ideas from [Discourse](http://www.discourse.org/), and look at
its database structure, HTTP requests, and avatar pixel width. Discourse is
forum software.


License
-----------------------------

Currently AGPL — please let me know if you want me to change to GPL, contact info here: https://www.effectivediscussions.org/contact


    Copyright (c) 2010-2016  Kaj Magnus Lindberg

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.


vim: list et ts=2 sw=2 tw=0 fo=r
