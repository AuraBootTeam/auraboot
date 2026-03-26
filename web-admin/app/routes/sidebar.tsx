/** Icons are imported separatly to reduce build time */
import DocumentTextIcon from '@heroicons/react/24/outline/DocumentTextIcon';
import Squares2X2Icon from '@heroicons/react/24/outline/Squares2X2Icon';
import TableCellsIcon from '@heroicons/react/24/outline/TableCellsIcon';
import WalletIcon from '@heroicons/react/24/outline/WalletIcon';
import CodeBracketSquareIcon from '@heroicons/react/24/outline/CodeBracketSquareIcon';
import CalendarDaysIcon from '@heroicons/react/24/outline/CalendarDaysIcon';
import ArrowRightOnRectangleIcon from '@heroicons/react/24/outline/ArrowRightOnRectangleIcon';
import UserIcon from '@heroicons/react/24/outline/UserIcon';
import Cog6ToothIcon from '@heroicons/react/24/outline/Cog6ToothIcon';
import BoltIcon from '@heroicons/react/24/outline/BoltIcon';
import ChartBarIcon from '@heroicons/react/24/outline/ChartBarIcon';
import CurrencyDollarIcon from '@heroicons/react/24/outline/CurrencyDollarIcon';
import InboxArrowDownIcon from '@heroicons/react/24/outline/InboxArrowDownIcon';
import UsersIcon from '@heroicons/react/24/outline/UsersIcon';
import KeyIcon from '@heroicons/react/24/outline/KeyIcon';
import DocumentDuplicateIcon from '@heroicons/react/24/outline/DocumentDuplicateIcon';
import ComputerDesktopIcon from '@heroicons/react/24/outline/ComputerDesktopIcon';
import FilmIcon from '@heroicons/react/24/outline/FilmIcon';
import RectangleStackIcon from '@heroicons/react/24/outline/RectangleStackIcon';
import PhotoIcon from '@heroicons/react/24/outline/PhotoIcon';
import ChartPieIcon from '@heroicons/react/24/outline/ChartPieIcon';
import RectangleGroupIcon from '@heroicons/react/24/outline/RectangleGroupIcon';
import QrCodeIcon from '@heroicons/react/24/outline/QrCodeIcon';
import PresentationChartBarIcon from '@heroicons/react/24/outline/PresentationChartBarIcon';

const iconClasses = `h-6 w-6`;
const submenuIconClasses = `h-5 w-5`;

const routes = [
  {
    path: '/page/new/1',
    icon: <Squares2X2Icon className={iconClasses} />,
    name: 'CreateForm',
  },
  {
    path: '/page/list/2', // url
    icon: <InboxArrowDownIcon className={iconClasses} />, // icon component
    name: 'FormList', // name that appear in Sidebar
  },
  {
    path: '/designer', // url
    icon: <CurrencyDollarIcon className={iconClasses} />,
    name: 'FormDesigner',
  },
  {
    path: '/list/item', // url
    icon: <ChartBarIcon className={iconClasses} />, // icon component
    name: 'ItemList', // name that appear in Sidebar
  },
  {
    path: '/app/integration', // url
    icon: <BoltIcon className={iconClasses} />, // icon component
    name: 'Integration', // name that appear in Sidebar
  },
  {
    path: '/app/calendar', // url
    icon: <CalendarDaysIcon className={iconClasses} />, // icon component
    name: 'Calendar', // name that appear in Sidebar
  },
  {
    path: '', //no url needed as this has submenu
    icon: <ComputerDesktopIcon className={`${iconClasses} inline`} />, // icon component
    name: '设备管理', // name that appear in Sidebar
    submenu: [
      {
        path: '/devices',
        icon: <ComputerDesktopIcon className={submenuIconClasses} />,
        name: '设备列表',
      },
      {
        path: '/devices/groups',
        icon: <RectangleGroupIcon className={submenuIconClasses} />,
        name: '分组管理',
      },
      {
        path: '/devices/monitor',
        icon: <ChartPieIcon className={submenuIconClasses} />,
        name: '监控面板',
      },
    ],
  },
  {
    path: '', //no url needed as this has submenu
    icon: <FilmIcon className={`${iconClasses} inline`} />, // icon component
    name: '节目管理', // name that appear in Sidebar
    submenu: [
      {
        path: '/content/library',
        icon: <PhotoIcon className={submenuIconClasses} />,
        name: '内容库',
      },
      {
        path: '/content/programs',
        icon: <FilmIcon className={submenuIconClasses} />,
        name: '节目编辑器',
      },
      {
        path: '/content/templates',
        icon: <RectangleStackIcon className={submenuIconClasses} />,
        name: '模板管理',
      },
    ],
  },
  {
    path: '', //no url needed as this has submenu
    icon: <PresentationChartBarIcon className={`${iconClasses} inline`} />, // icon component
    name: '报表管理', // name that appear in Sidebar
    submenu: [
      {
        path: '/reports/overview',
        icon: <ChartBarIcon className={submenuIconClasses} />,
        name: '报表概览',
      },
    ],
  },

  {
    path: '', //no url needed as this has submenu
    icon: <DocumentDuplicateIcon className={`${iconClasses} inline`} />, // icon component
    name: 'Pages', // name that appear in Sidebar
    submenu: [
      {
        path: '/login',
        icon: <ArrowRightOnRectangleIcon className={submenuIconClasses} />,
        name: 'Login',
      },
      {
        path: '/h5-scan',
        icon: <QrCodeIcon className={submenuIconClasses} />,
        name: '扫码登录',
      },
      {
        path: '/register', //url
        icon: <UserIcon className={submenuIconClasses} />, // icon component
        name: 'Register', // name that appear in Sidebar
      },
      {
        path: '/forgot-password',
        icon: <KeyIcon className={submenuIconClasses} />,
        name: 'Forgot Password',
      },
    ],
  },
  {
    path: '', //no url needed as this has submenu
    icon: <Cog6ToothIcon className={`${iconClasses} inline`} />, // icon component
    name: 'Settings', // name that appear in Sidebar
    submenu: [
      {
        path: '/app/settings-profile', //url
        icon: <UserIcon className={submenuIconClasses} />, // icon component
        name: 'Profile', // name that appear in Sidebar
      },
      {
        path: '/app/settings-billing',
        icon: <WalletIcon className={submenuIconClasses} />,
        name: 'Billing',
      },
      {
        path: '/app/settings-team', // url
        icon: <UsersIcon className={submenuIconClasses} />, // icon component
        name: 'Team Members', // name that appear in Sidebar
      },
    ],
  },
  {
    path: '', //no url needed as this has submenu
    icon: <DocumentTextIcon className={`${iconClasses} inline`} />, // icon component
    name: 'Documentation', // name that appear in Sidebar
    submenu: [
      {
        path: '/app/getting-started', // url
        icon: <DocumentTextIcon className={submenuIconClasses} />, // icon component
        name: 'Getting Started', // name that appear in Sidebar
      },
      {
        path: '/app/features',
        icon: <TableCellsIcon className={submenuIconClasses} />,
        name: 'Features',
      },
      {
        path: '/app/components',
        icon: <CodeBracketSquareIcon className={submenuIconClasses} />,
        name: 'Components',
      },
    ],
  },
];

export default routes;
