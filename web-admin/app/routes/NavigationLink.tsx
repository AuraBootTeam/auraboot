import type { ComponentProps } from 'react';
import { NavLink } from 'react-router';

/** One renderer for expanded, collapsed and nested navigation links. */
export default function NavigationLink({
  opening,
  ...props
}: ComponentProps<typeof NavLink> & { opening?: string }) {
  const external = typeof props.to === 'string' && /^https?:\/\//i.test(props.to);
  return (
    <NavLink
      {...props}
      reloadDocument={external || opening === 'new'}
      target={opening === 'new' ? '_blank' : undefined}
      rel={opening === 'new' ? 'noopener noreferrer' : undefined}
    />
  );
}
