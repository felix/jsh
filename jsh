#!/bin/sh

[ -z "${DEBUG}" ] || set -x
set -e

RELEASE="${RELEASE:-"$(sysctl -n kern.osrelease |sed -e 's/-p[0-9]*$//')"}"
ZROOT=${ZROOT:-zroot/jails}
JROOT=${JROOT:-/usr/local/jails}
JARCH=${JARCH:-$(sysctl -n hw.machine_arch)}
JDIST=".releases/$RELEASE"
JTMPL=".templates/$RELEASE/root"
JSKEL=".templates/$RELEASE/skel"
JSKELTMPL="$JROOT/.templates/skel"
DIST_SRC=${DIST_SRC:-http://mirror.internode.on.net/pub/FreeBSD/releases/${JARCH}/${RELEASE}}

err() {
	printf "[%s] %s\n" "$(date -Iseconds)" "ERROR: $1"
	exit 1
}

log() { [ -z "$QUIET" ] && printf "[%s] %s\n" "$(date -Iseconds)" "$1"; }

create_release() {
	if [ ! -d "$JROOT" ]; then
		log "Creating global datasets..."
		zfs create -o compression=lz4 -o mountpoint="$JROOT" -p "$ZROOT"
	fi
	if [ ! -d "$JROOT/$JDIST" ]; then
		log "Creating release datasets..."
		zfs create -p "$ZROOT/$JDIST"
	fi

	create_template

	for f in base.txz lib32.txz; do
		if [ ! -f "$JROOT/$JDIST/$f" ]; then
			log "Fetching $JDIST/$f..."
			fetch -m -o "$JROOT/$JDIST/$f" "$DIST_SRC/$f"

			log "Extracting $JDIST/$f..."
			mkdir -p "$JROOT/$JTMPL"
			tar -C "$JROOT/$JTMPL" -xkf "$JROOT/$JDIST/$f"
		fi
	done

	update_release
}

update_release() {
	sync_template
	log "Updating $JDIST/$f..."
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" fetch install
	env UNAME_r="$RELEASE" freebsd-update -b "$JROOT/$JTMPL" IDS
}

delete_release() {
	[ -z "$1" ] && err "Release name required"
	[ "$1" = "$RELEASE" ] && err "Cannot delete current release"
	zfs list "$ZROOT/.releases/$1">/dev/null || err "No release found"
	log "Deleting $ZROOT/.releases/$1..."
	zfs destroy -r "$ZROOT/.releases/$1"
}

create_template() {
	if [ ! -d "$JROOT/$JTMPL" ]; then
		log "Creating template datasets..."
		zfs create -p "$ZROOT/$JTMPL"
	fi
	if [ ! -d "$JROOT/$JSKEL" ]; then
		log "Creating skeleton datasets..."
		zfs create -p "$ZROOT/$JSKEL"
	fi
}

sync_template() {
	create_template
	log "Syncing template skeleton..."
	[ -e "$JROOT/$JTMPL/var/empty" ] && chflags noschg "$JROOT/$JTMPL/var/empty"
	for d in etc usr/local tmp var root; do
		if [ -d "$JROOT/$JTMPL/$d" ]; then
			#log "Syncing $JTMPL/$d..."
			#rsync -Pa --update "$JROOT/$JTMPL/$d/" "$JROOT/$JSKEL/$d/"
			if [ -d "$JROOT/$JSKEL/$d" ]; then
				err "$JROOT/$JSKEL/$d exists"
			else
				log "Moving $JTMPL/$d..."
				mkdir -p "$(dirname "$JROOT/$JSKEL/$d")"
				mv -iv "$JROOT/$JTMPL/$d" "$JROOT/$JSKEL/$d"
			fi
		fi
	done
	[ -e "$JROOT/$JSKEL/var/empty" ] && chflags schg "$JROOT/$JSKEL/var/empty"

	log "Linking template overlay..."
	cd "$JROOT/$JTMPL"
	mkdir -p s
	for d in etc tmp var root; do
		if [ -e "$d" ]; then
			err "$JROOT/$JTMPL/$d exists"
			#mv "$d" "$d.old"
		fi
		log "Linking $JTMPL/$d..."
		ln -sf "s/$d" "$d"
	done
	# usr/local is different
	cd usr && ln -sf "../s/usr/local" "local"

	# seed skel
	if [ -d "$JSKELTMPL" ]; then
		log "Syncing custom skeleton"
		cp -av "$JSKELTMPL/" "$JROOT/$JSKEL/"
	fi
	log "Remember to check/create $JROOT/$JSKEL/etc/rc.conf"
}

delete_template() {
	[ -z "$1" ] && err "Release name required"
	zfs list "$ZROOT/.templates/$1">/dev/null || err "Template for $1 not found"
	log "Deleting $ZROOT/.templates/$1..."
	zfs destroy -r "$ZROOT/.templates/$1"
}

create_jail() {
	name=$1
	ip="$2"
	[ -z "$name" ] && err "Jail name required"
	[ -z "$ip" ] && err "ID required"

	log "Creating overlay..."
	[ ! -e "$ZROOT/$name" ] && zfs create -p "$ZROOT/$name"
	[ ! -d "$ZROOT/$name/root" ] && mkdir -p "$JROOT/$name/root"
	[ ! -d "$ZROOT/$name/overlay" ] && mkdir -p "$JROOT/$name/overlay"
	rsync -auqv --partial "$JROOT/$JSKEL/" "$JROOT/$name/overlay"
	sysrc -f "$JROOT/$name/overlay/etc/rc.conf" hostname="$name" >/dev/null

	if grep -qv -e "^$name" /etc/jail.conf; then echo "$name { \$id = $ip; }" >>/etc/jail.conf; fi
	cat << EOF >"$JROOT/$name/overlay/etc/fstab"
$JROOT/$JTMPL	$JROOT/$name/root nullfs   ro          0 0
$JROOT/$name/overlay	$JROOT/$name/root/s nullfs  rw  0 0
EOF
	cd "$JROOT/$name" && ln -s overlay/etc/fstab fstab
}

start_jail() {
	[ -z "$1" ] && err "Jail name required"

	while [ -n "$1" ]; do
		jls -j "$1" >/dev/null 2>&1 && err "Jail $1 already running"
		jail -q -c "$1" && log "Started $1"
		shift
	done
}

stop_jail() {
	[ -z "$1" ] && err "Jail name required"

	while [ -n "$1" ]; do
		if [ "$(jls host.hostname |grep -c "^$1")" = "1" ]; then
			jail -qr "$1" && log "Stopped $1"
		else
			err "Jail $1 is not running"
		fi
		shift
	done

}

jail_shell() {
	name="$1"
	[ -z "$name" ] && err "Jail name required"
	shift

	if [ "$(jls host.hostname jid |grep -c "^$name")" != "1" ]; then
		err "Jail $1 not running"
	fi

	if [ -z "$*" ]; then
		/usr/sbin/jexec "$name" /bin/sh -
	else
		/usr/sbin/jexec "$name" "$@"
	fi
}

jail_list() {
	jail -e'|' | \
		#awk -F'|' '{ delete vars; for(i = 1; i <= NF; ++i) { n = index($i, "="); if(n) { vars[substr($i, 1, n - 1)] = substr($i, n + 1) } } name = vars["name"]} { print name }' /etc/jail.conf
	awk '/[-a-z]+ {/ { print $1 }' /etc/jail.conf
	# awk '/[-a-z]+ {/ { print $1 }' /etc/jail.conf | while read -r name; do
	# 	line=$(jls -j "$name" host.hostname jid)
	# 	if [ $? -eq 0 ]; then
	# 		echo "$line"
	# 	else
	# 		printf '%s -\n' "$name"
	# 	fi
	#	#printf "%s\n" "$name"
	#	#jls host.hostname ip4.addr
	#done
}

jail_chroot() {
	name="$1"
	[ -z "$name" ] && err "Jail name required"
	path="$JROOT/$name/root"
	echo "--> $path"
	env SHELL= chroot "$path"
}

delete_jail() {
	name="$1"
	[ -z "$name" ] && err "Jail name required"

	if [ "$(jls host.hostname jid |grep -c "^$name")" = "1" ]; then
		jail -qr "$name"
	fi

	zfs destroy -r "$ZROOT/$name"
	sed -i.bak -e "/^$name/d" /etc/jail.conf
}

template_cmd() {
	cmd="${1:-help}"
	shift || true

	case "$cmd" in
		sync)
			sync_template
			;;
		delete)
			delete_template "$@"
			;;
		*)
			cat <<EOM
Usage: template [cmd]
sync                    - Sync template for current release
delete                  - Delete template for named release
EOM
			;;
	esac
}

release_cmd() {
	cmd="${1:-help}"
	shift || true

	case "$cmd" in
		create)
			create_release
			;;
		update)
			update_release
			;;
		delete)
			delete_release "$@"
			;;
		*)
			cat <<EOM
Usage: release [cmd]
sync                    - Create fs, fetch, extract & update
update                  - Update release using freebsd-update
delete                  - Delete named release
EOM
			;;
	esac
}


main() {
	while getopts ":q" opt; do
		case $opt in
			q) QUIET=true ;;
			?)
				usage
				exit 0
				;;
		esac
	done

	# Shift the rest
	shift $((OPTIND - 1))

	cmd="${1:-help}"
	shift || true

	case "$cmd" in
		release)
			release_cmd "$@"
			;;
		template)
			template_cmd "$@"
			;;
		create|c)
			create_jail "$@"
			;;
		delete)
			delete_jail "$@"
			;;
		chroot)
			jail_chroot "$@"
			;;
		list|ls)
			jail_list "$@"
			;;
		start)
			start_jail "$@"
			;;
		stop)
			stop_jail "$@"
			;;
		restart)
			stop_jail "$@" && start_jail "$@"
			;;
		shell)
			jail_shell "$@"
			;;
		*)
			cat <<EOM
Usage: jsh [cmd]
start <name>            - Start jail <name>
stop <name>             - Stop jail <name>
shell <name>            - Start a shell in jail <name>
create <name> [id]      - Create jail <name>
delete <name>           - Delete jail <name>
release                 - Release sub-commands
template                - Template sub-commands
EOM
			;;
	esac
}

main "$@"

