#!/bin/sh
# shellcheck disable=SC2039

[ -z "${DEBUG}" ] || set -x
set -e

RELEASE="${RELEASE:-"$(sysctl -n kern.osrelease |sed -e 's/-p[0-9]*$//')"}"
ZROOT=${ZROOT:-zroot/jails}
JROOT=${JROOT:-/usr/local/jails}
JARCH=${JARCH:-$(sysctl -n hw.machine_arch)}
JDIST=".releases/$RELEASE"
JTMPL=".templates/$RELEASE/root"
JSKEL=".templates/$RELEASE/skel"
DIST_SRC=${DIST_SRC:-http://mirror.internode.on.net/pub/FreeBSD/releases/${JARCH}/${RELEASE}}

#JAIL_IP=${JAIL_IP:-172.16.0.%d}

_err() {
	_log "ERROR: $1"
	exit 1
}

_log() {
	printf "%s\n" "$1"
}

_create_zfs_datasets() {
	_log "Creating global datasets..."
	[ ! -d "$ZROOT" ] && zfs create -o compression=lz4 -o mountpoint="$JROOT" -p "$ZROOT"
	[ ! -d "$ZROOT/$JDIST" ] && zfs create -p "$ZROOT/$JDIST"
}

_release_fetch() {
	for f in base.txz lib32.txz
	do
		if [ ! -f "$JROOT/$JDIST/$f" ]
		then
			_log "Fetching $JDIST/$f..."
			fetch -m -o "$JROOT/$JDIST/$f" "$DIST_SRC/$f"
		fi
	done
}

_release_extract() {
	_template_create_zfs
	for f in base.txz lib32.txz
	do
		if [ -f "$JROOT/$JDIST/$f" ]
		then
			_log "Extracting $JDIST/$f..."
			tar -C "$JROOT/$JTMPL" -xkf "$JROOT/$JDIST/$f"
		fi
	done
}

_release_update() {
	_log "Updating $JDIST/$f..."
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" fetch install
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" IDS
}

_template_create_zfs() {
	[ ! -d "$ZROOT/$JTMPL" ] && zfs create -p "$ZROOT/$JTMPL"
	[ ! -d "$ZROOT/$JSKEL" ] && zfs create -p "$ZROOT/$JSKEL"
}

_template_update() {
	_template_create_zfs
	[ -e "$JROOT/$JTMPL/var/empty" ] && chflags noschg "$JROOT/$JTMPL/var/empty"
	for d in etc usr/local tmp var root; do
		if [ -d "$JROOT/$JTMPL/$d" ]; then
			_log "Moving $JTMPL/$d..."
			mkdir -p "$(dirname "$JROOT/$JSKEL/$d")"
			mv "$JROOT/$JTMPL/$d" "$JROOT/$JSKEL/$d"
		fi
	done
	cp /etc/resolv.conf "$JROOT/$JSKEL/etc/resolv.conf"
	[ -e "$JROOT/$JSKEL/var/empty" ] && chflags schg "$JROOT/$JSKEL/var/empty"

	cd "$JROOT/$JTMPL"
	mkdir -p s
	for d in etc tmp var root; do
		_log "Linking $JTMPL/$d..."
		ln -sf "s/$d" "$d"
	done
	# usr/local is different
	cd usr && ln -sf "../s/usr/local" "local"
}

_jail_new_overlay() {
	local name=$1
	[ -z "$name" ] && _err "Cannot create overlay: missing name"

	_log "Creating overlay..."
	[ ! -e "$ZROOT/$name" ] && zfs create -p "$ZROOT/$name"
	[ ! -d "$ZROOT/$name/root" ] && mkdir -p "$JROOT/$name/root"
	[ ! -d "$ZROOT/$name/overlay" ] && mkdir -p "$JROOT/$name/overlay"
	rsync -auqv --partial "$JROOT/$JSKEL/" "$JROOT/$name/overlay"
	sysrc -f "$JROOT/$name/overlay/etc/rc.conf" hostname="$name" >/dev/null
}

#_max_ip() {
#	return "$(jls ip4.addr | cut -d'.' -f4 | sort -n | tail -1)"
#}

_jail_new_config() {
	local name="$1"
	local ip="$2"
	[ -z "$name" ] && _err "Cannot create jail config: missing name"
	[ -z "$ip" ] && _err "Cannot set IP: missing IP address"
	if grep -qv -e "^$name" /etc/jail.conf; then echo "$name { \$ip = $ip; }" >>/etc/jail.conf; fi
	_jail_update_fstab "$name"
}

_jail_update_fstab() {
	local name="$1"
	[ -z "$name" ] && _err "Cannot create jail config: missing name"
	cat << EOF >"$JROOT/$name/fstab"
$JROOT/$JTMPL	$JROOT/$name/root nullfs   ro          0 0
$JROOT/$name/overlay	$JROOT/$name/root/s nullfs  rw  0 0
EOF
}

_jail_start() {
	local name="$1"; shift
	[ -z "$name" ] && _err "Cannot start jail: missing name"

	if jls -j "$name" >/dev/null 2>&1; then
		_err "Jail $name already running"
	fi
	jail -c "$name"
	[ "$1" == "-c" ] && _jail_shell "$name"
}

_jail_stop() {
	local name="$1"
	[ -z "$name" ] && _err "Cannot stop jail: missing name"

	if [ "$(jls host.hostname jid |grep -c "^$name")" == "1" ]; then
		jail -qr "$name"
	else
		_log "Jail $name is not running"
	fi
}

_jail_shell() {
	local name="$1"
	[ -z "$name" ] && _err "Cannot start shell: missing name"
	shift

	if [ "$(jls host.hostname jid |grep -c "^$name")" != "1" ]; then
		_err "Jail not running"
	fi

	if [ -z "$*" ]; then
		/usr/sbin/jexec "$name" /bin/sh -
	else
		/usr/sbin/jexec "$name" "$@"
	fi
}

_jail_list() {
	ls -1I "$JROOT"
}

_jail_chroot() {
	local name="$1"
	[ -z "$name" ] && _err "Cannot start chroot: missing name"
	local path="$JROOT/$name/root"
	echo "--> $path"
	env SHELL= chroot "$path"
}

_jail_delete() {
	local name="$1"
	[ -z "$name" ] && _err "Cannot drop jail: missing name"

	if [ "$(jls host.hostname jid |grep -c "^$name")" == "1" ]; then
		jail -qr "$name"
	fi

	zfs destroy -r "$ZROOT/$name"
	sed -i.bak -e "/^$name/d" /etc/jail.conf
}

_cmd_template() {
	local cmd="${1:-help}"
	shift || true

	case "$cmd" in
		sync)
		      	_template_update
			;;
		*)
			cat <<EOM
Usage: template [cmd]
sync                    - Sync template for current release
EOM
			;;
	esac
}

_cmd_release() {
	local cmd="${1:-help}"
	shift || true

	case "$cmd" in
		sync)
			_create_zfs_datasets
		      	_release_fetch
			_release_extract
			_release_update
			;;
		fetch)
		      	_release_fetch
			;;
		extract)
			_release_extract
			;;
		update)
			_release_update
			;;
		*)
			cat <<EOM
Usage: release [cmd]
sync                    - Download/unpack/update release
fetch                   - Download distfiles for latest release
extract                 - Unpack latest release
update                  - Update latest release using freebsd-update
EOM
			;;
	esac
}


_main() {
	local cmd="${1:-help}"
	shift || true

	case "$cmd" in
		release)
			_cmd_release "$@"
			;;
		template)
			_cmd_template "$@"
			;;
		create|c)
			_jail_new_overlay "$@"
			_jail_new_config "$@"
			;;
		delete)
			_jail_delete "$@"
			;;
		chroot)
		    	_jail_chroot "$@"
			;;
		list|ls)
		   	_jail_list "$@"
			;;
		start)
		   	_jail_start "$@"
			;;
		stop)
		  	_jail_stop "$@"
			;;
		restart)
		   	_jail_stop "$@" && _jail_start "$@"
			;;
		shell)
		       	_jail_shell "$@"
		       	;;
		upgrade)
		       	_jail_stop "$@"
		       	_jail_update_fstab "$@"
		       	_jail_start "$@"
		       	;;
		*)
			cat <<EOM
Usage: jsh [cmd]
start <name>            - Start jail <name>
stop <name>             - Stop jail <name>
shell <name>            - Start a shell in jail <name>
create <name> [id]      - Create jail <name>
upgrade <name>          - Upgrade jail <name>
delete <name>           - Delete jail <name>
release                 - Release sub-commands
template                - Template sub-commands
EOM
			;;
	esac
}

_main "$@"

