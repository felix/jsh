# jsh: Jail shell

My own personal FreeBSD jail management script in POSIX shell.

It can be used to configure and maintain FreeBSD jails using only the built in
jail management tools.

- ZFS filesystems for releases, templates and jails.
- Minimal reuse by using an overlay for each jail.
- Ability to update the base filesystem for all jails.

This is very much a work-in-progress and things are added as I need them or
they break.

**Not recommended for production**

## Install

```sh
# cp jsh /usr/local/sbin/
```

## Usage

```shell
$ jsh help
Usage: jsh [cmd]
start <name>            - Start jail <name>
stop <name>             - Stop jail <name>
shell <name>            - Start a shell in jail <name>
create <name> [id]      - Create jail <name>
delete <name>           - Delete jail <name>
release                 - Release sub-commands
template                - Template sub-commands
```

```shell
$ jsh release help
Usage: release [cmd]
sync                    - Create fs, fetch, extract & update
update                  - Update release using freebsd-update
delete                  - Delete named release
```

```shell
$ jsh template help
Usage: template [cmd]
sync                    - Sync template for current release
delete                  - Delete template for named release
```
