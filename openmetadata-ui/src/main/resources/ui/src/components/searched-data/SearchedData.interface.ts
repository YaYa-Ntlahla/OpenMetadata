/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import { ReactNode } from 'react';
import { Table } from '../../generated/entity/data/table';
import { EntityReference } from '../../generated/entity/type';
import { TagLabel } from '../../generated/type/tagLabel';
import {
  DashboardSearchSource,
  ExploreSearchSource,
  MlmodelSearchSource,
  SearchHitBody,
  TableSearchSource,
} from '../../interface/search.interface';
import { ExploreSearchIndex } from '../Explore/explore.interface';

type Fields =
  | 'name'
  | 'fullyQualifiedName'
  | 'description'
  | 'serviceType'
  | 'deleted';

export type SourceType = (
  | Pick<
      TableSearchSource,
      Fields | 'usageSummary' | 'database' | 'databaseSchema' | 'tableType'
    >
  | Pick<DashboardSearchSource | MlmodelSearchSource, Fields | 'usageSummary'>
  | Pick<
      Exclude<
        ExploreSearchSource,
        TableSearchSource | DashboardSearchSource | MlmodelSearchSource
      >,
      Fields
    >
) & {
  tier?: string | Pick<TagLabel, 'tagFQN'>;
  tags?: string[] | TagLabel[];
  owner?: Partial<
    Pick<
      EntityReference,
      'name' | 'displayName' | 'id' | 'type' | 'fullyQualifiedName' | 'deleted'
    >
  >;
};

export interface SearchedDataProps {
  children?: ReactNode;
  data: SearchHitBody<ExploreSearchIndex, SourceType>[];
  currentPage: number;
  isLoading?: boolean;
  paginate: (value: string | number) => void;
  totalValue: number;
  fetchLeftPanel?: () => ReactNode;
  showResultCount?: boolean;
  searchText?: string;
  showOnboardingTemplate?: boolean;
  showOnlyChildren?: boolean;
  isFilterSelected: boolean;
  handleSummaryPanelDisplay?: (source: Table) => void;
}
