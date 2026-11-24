import type { ComponentProps } from "react";
import { NavLink, Form, useLocation } from "react-router";
import { useRootLoaderData } from "~/root-data";

type NavigationProps = ComponentProps<typeof NavLink> & { opening?: string };

function PlatformConsoleLink(props: ComponentProps<typeof NavLink>) {
  const root = useRootLoaderData();
  const space = root?.spaces?.find(
    (space) =>
      space.spaceType === "platform" &&
      space.roleCodes?.includes("platform_admin"),
  );
  if (!space) return null;
  const state = { isActive: false, isPending: false, isTransitioning: false };
  const className =
    typeof props.className === "function"
      ? props.className(state)
      : props.className;
  return (
    <Form method="post" action="/_action/switch-space" className="w-full">
      <input type="hidden" name="tenantId" value={space.tenantId} />
      <input type="hidden" name="redirectTo" value="/platform/plugins" />
      <button
        type="submit"
        className={`${className || ""} w-full text-start`}
        title={props.title}
        data-testid="platform-console-link"
      >
        {typeof props.children === "function"
          ? props.children(state)
          : props.children}
      </button>
    </Form>
  );
}

export function matchesNavigationTarget(
  pathname: string,
  search: string,
  path: string,
) {
  const target = new URL(path, "http://navigation.local");
  const current = new URLSearchParams(search);
  return (
    pathname === target.pathname &&
    [...target.searchParams].every(([key, value]) => current.get(key) === value)
  );
}

/** One renderer for expanded, collapsed and nested navigation links. */
export default function NavigationLink({ opening, ...props }: NavigationProps) {
  const location = useLocation();
  if (opening === "platform") return <PlatformConsoleLink {...props} />;
  const external =
    typeof props.to === "string" && /^https?:\/\//i.test(props.to);
  const queryMismatch =
    typeof props.to === "string" &&
    props.to.includes("?") &&
    !matchesNavigationTarget(location.pathname, location.search, props.to);
  return (
    <NavLink
      {...props}
      aria-current={queryMismatch ? false : props["aria-current"]}
      className={
        typeof props.className === "function"
          ? (state) =>
              (props.className as (value: typeof state) => string)({
                ...state,
                isActive: state.isActive && !queryMismatch,
              })
          : props.className
      }
      reloadDocument={external || opening === "new"}
      target={opening === "new" ? "_blank" : undefined}
      rel={opening === "new" ? "noopener noreferrer" : undefined}
    />
  );
}
