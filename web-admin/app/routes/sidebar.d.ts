declare module '../routes/sidebar' {
  const routes: {
    path: string;
    name: string;
    icon: React.ReactNode;
    submenu?: {
      path: string;
      name: string;
      icon: React.ReactNode;
    }[];
  }[];

  export default routes;
}
